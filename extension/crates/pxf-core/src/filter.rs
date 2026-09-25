// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

//! PXF reverse-polish wire format. Expression classification, type output,
//! collation checks and SQL NULL semantics belong to the database adapter.
//! Only predicates proven safe by that adapter may be constructed here.

use std::io::Write;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Comparison {
    Less = 1,
    Greater = 2,
    LessEqual = 3,
    GreaterEqual = 4,
    Equal = 5,
    NotEqual = 6,
    Like = 7,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Scalar {
    pub type_oid: u32,
    /// Database output-function representation in the database encoding.
    pub value: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Filter {
    Compare {
        /// PXF column indexes are zero-based (PostgreSQL attnums are not).
        column: u16,
        operator: Comparison,
        value: Scalar,
        constant_on_left: bool,
    },
    IsNull {
        column: u16,
        negated: bool,
    },
    In {
        column: u16,
        array_type_oid: u32,
        values: Vec<Vec<u8>>,
    },
    And(Box<Filter>, Box<Filter>),
    Or(Box<Filter>, Box<Filter>),
    Not(Box<Filter>),
}

impl Filter {
    /// Iterative traversal avoids overflowing the stack while serializing
    /// deeply nested planner expressions. Lengths count bytes, not characters.
    pub fn serialize(&self) -> Vec<u8> {
        enum Item<'a> {
            Filter(&'a Filter),
            Logical(u8),
        }
        let mut output = Vec::new();
        let mut pending = vec![Item::Filter(self)];
        while let Some(item) = pending.pop() {
            match item {
                Item::Logical(code) => write!(output, "l{code}").unwrap(),
                Item::Filter(Self::And(left, right) | Self::Or(left, right)) => {
                    let code = if matches!(item, Item::Filter(Self::And(..))) {
                        0
                    } else {
                        1
                    };
                    pending.push(Item::Logical(code));
                    pending.push(Item::Filter(right));
                    pending.push(Item::Filter(left));
                }
                Item::Filter(Self::Not(child)) => {
                    pending.push(Item::Logical(2));
                    pending.push(Item::Filter(child));
                }
                Item::Filter(Self::Compare {
                    column,
                    operator,
                    value,
                    constant_on_left,
                }) => {
                    if !constant_on_left {
                        write!(output, "a{column}").unwrap();
                    }
                    write!(output, "c{}s{}d", value.type_oid, value.value.len()).unwrap();
                    output.extend_from_slice(&value.value);
                    if *constant_on_left {
                        write!(output, "a{column}").unwrap();
                    }
                    write!(output, "o{}", *operator as u8).unwrap();
                }
                Item::Filter(Self::IsNull { column, negated }) => {
                    write!(output, "a{column}o{}", if *negated { 9 } else { 8 }).unwrap();
                }
                Item::Filter(Self::In {
                    column,
                    array_type_oid,
                    values,
                }) => {
                    write!(output, "a{column}m{array_type_oid}").unwrap();
                    for value in values {
                        write!(output, "s{}d", value.len()).unwrap();
                        output.extend_from_slice(value);
                    }
                    output.extend_from_slice(b"o10");
                }
            }
        }
        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn compare(column: u16, operator: Comparison, type_oid: u32, value: &[u8]) -> Filter {
        Filter::Compare {
            column,
            operator,
            value: Scalar {
                type_oid,
                value: value.to_vec(),
            },
            constant_on_left: false,
        }
    }

    #[test]
    fn matches_legacy_documented_rpn_example() {
        let filter = Filter::And(
            Box::new(compare(0, Comparison::Greater, 23, b"1")),
            Box::new(Filter::And(
                Box::new(compare(1, Comparison::Less, 23, b"5")),
                Box::new(compare(2, Comparison::Equal, 25, b"third")),
            )),
        );
        assert_eq!(
            filter.serialize(),
            b"a0c23s1d1o2a1c23s1d5o1a2c25s5dthirdo5l0l0"
        );
    }

    #[test]
    fn byte_lengths_and_operand_order_are_preserved() {
        assert_eq!(
            compare(0, Comparison::Equal, 25, "ёж".as_bytes()).serialize(),
            "a0c25s4dёжo5".as_bytes()
        );
        let filter = Filter::Compare {
            column: 2,
            operator: Comparison::Less,
            value: Scalar {
                type_oid: 23,
                value: b"3".to_vec(),
            },
            constant_on_left: true,
        };
        assert_eq!(filter.serialize(), b"c23s1d3a2o1");
    }

    #[test]
    fn null_list_and_boolean_operators_match_wire_codes() {
        let filter = Filter::Not(Box::new(Filter::Or(
            Box::new(Filter::IsNull {
                column: 0,
                negated: false,
            }),
            Box::new(Filter::In {
                column: 1,
                array_type_oid: 1007,
                values: vec![b"1".to_vec(), b"22".to_vec()],
            }),
        )));
        assert_eq!(filter.serialize(), b"a0o8a1m1007s1d1s2d22o10l1l2");
    }
}
