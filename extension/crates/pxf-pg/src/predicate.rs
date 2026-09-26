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
//! Conservative extraction: every predicate remains a local executor qual.
//! An unsupported subtree under OR/NOT disables that entire subtree.
use crate::{
    abi,
    context::{bytes, list},
};
use pgrx::pg_sys;
use pxf_core::filter::{Comparison, Filter, Scalar};
use std::ptr::null_mut;

fn supported(oid: pg_sys::Oid) -> bool {
    [
        16, 18, 20, 21, 23, 25, 700, 701, 1042, 1043, 1082, 1114, 1700,
    ]
    .contains(&oid.as_u32())
}

unsafe fn variable(
    mut node: *mut pg_sys::Node,
    relid: Option<u32>,
    relation: pg_sys::Relation,
) -> Option<u16> {
    unsafe {
        if node.is_null() {
            return None;
        }
        if (*node).type_ == pg_sys::NodeTag::T_RelabelType {
            node = (*node.cast::<pg_sys::RelabelType>()).arg.cast();
        }
        if node.is_null() || (*node).type_ != pg_sys::NodeTag::T_Var {
            return None;
        }
        let var = &*node.cast::<pg_sys::Var>();
        if var.varlevelsup != 0 || relid.is_some_and(|id| id != var.varno) || var.varattno <= 0 {
            return None;
        }
        let desc = (*relation).rd_att;
        let attrs = (*desc).attrs.as_slice((*desc).natts as usize);
        let index = var.varattno as usize - 1;
        if attrs.get(index)?.attisdropped {
            return None;
        }
        u16::try_from(attrs[..index].iter().filter(|a| !a.attisdropped).count()).ok()
    }
}

unsafe fn scalar(value: pg_sys::Datum, oid: pg_sys::Oid) -> Option<Vec<u8>> {
    unsafe {
        if !supported(oid) {
            return None;
        }
        if oid == pg_sys::BOOLOID {
            return Some(if value.value() == 0 {
                b"false".to_vec()
            } else {
                b"true".to_vec()
            });
        }
        let mut function = pg_sys::InvalidOid;
        let mut varlena = false;
        pg_sys::getTypeOutputInfo(oid, &mut function, &mut varlena);
        let value = pg_sys::OidOutputFunctionCall(function, value);
        let output = bytes(value);
        pg_sys::pfree(value.cast());
        if [
            b"NaN".as_slice(),
            b"Infinity",
            b"-Infinity",
            b"infinity",
            b"-infinity",
        ]
        .contains(&output.as_slice())
        {
            None
        } else {
            Some(output)
        }
    }
}

unsafe fn collation_supported(collation: pg_sys::Oid) -> bool {
    collation == pg_sys::InvalidOid || unsafe { abi::pxf_cb_collation_is_c(collation) }
}

unsafe fn expression(
    node: *mut pg_sys::Node,
    relid: Option<u32>,
    relation: pg_sys::Relation,
    depth: usize,
) -> Option<Filter> {
    unsafe {
        if node.is_null() || depth > 128 {
            return None;
        }
        match (*node).type_ {
            pg_sys::NodeTag::T_RestrictInfo => expression(
                (*node.cast::<pg_sys::RestrictInfo>()).clause.cast(),
                relid,
                relation,
                depth + 1,
            ),
            pg_sys::NodeTag::T_BoolExpr => {
                let expr = &*node.cast::<pg_sys::BoolExpr>();
                let args = list::<pg_sys::Node>(expr.args);
                if expr.boolop == pg_sys::BoolExprType::NOT_EXPR {
                    if args.len() != 1 {
                        return None;
                    }
                    return Some(Filter::Not(Box::new(expression(
                        args[0],
                        relid,
                        relation,
                        depth + 1,
                    )?)));
                }
                let mut args = args.into_iter();
                let mut result = expression(args.next()?, relid, relation, depth + 1)?;
                for arg in args {
                    let next = expression(arg, relid, relation, depth + 1)?;
                    result = if expr.boolop == pg_sys::BoolExprType::AND_EXPR {
                        Filter::And(Box::new(result), Box::new(next))
                    } else if expr.boolop == pg_sys::BoolExprType::OR_EXPR {
                        Filter::Or(Box::new(result), Box::new(next))
                    } else {
                        return None;
                    };
                }
                Some(result)
            }
            pg_sys::NodeTag::T_NullTest => {
                let expr = &*node.cast::<pg_sys::NullTest>();
                if expr.argisrow {
                    return None;
                }
                Some(Filter::IsNull {
                    column: variable(expr.arg.cast(), relid, relation)?,
                    negated: expr.nulltesttype == pg_sys::NullTestType::IS_NOT_NULL,
                })
            }
            pg_sys::NodeTag::T_Var => {
                if (*node.cast::<pg_sys::Var>()).vartype != pg_sys::BOOLOID {
                    return None;
                }
                Some(Filter::Compare {
                    column: variable(node, relid, relation)?,
                    operator: Comparison::Equal,
                    value: Scalar {
                        type_oid: 16,
                        value: b"true".to_vec(),
                    },
                    constant_on_left: false,
                })
            }
            pg_sys::NodeTag::T_OpExpr => {
                let expr = &*node.cast::<pg_sys::OpExpr>();
                if !collation_supported(expr.inputcollid) {
                    return None;
                }
                let operator = operator(expr.opno.as_u32())?;
                let args = list::<pg_sys::Node>(expr.args);
                if args.len() != 2 {
                    return None;
                }
                let (column, constant, reversed) =
                    if let Some(column) = variable(args[0], relid, relation) {
                        (column, args[1], false)
                    } else {
                        (variable(args[1], relid, relation)?, args[0], true)
                    };
                let constant = pg_sys::eval_const_expressions(null_mut(), constant);
                if constant.is_null() || (*constant).type_ != pg_sys::NodeTag::T_Const {
                    return None;
                }
                let constant = &*constant.cast::<pg_sys::Const>();
                if constant.constisnull {
                    return None;
                }
                Some(Filter::Compare {
                    column,
                    operator,
                    value: Scalar {
                        type_oid: constant.consttype.as_u32(),
                        value: scalar(constant.constvalue, constant.consttype)?,
                    },
                    constant_on_left: reversed,
                })
            }
            pg_sys::NodeTag::T_ScalarArrayOpExpr => {
                let expr = &*node.cast::<pg_sys::ScalarArrayOpExpr>();
                if !expr.useOr
                    || operator(expr.opno.as_u32()) != Some(Comparison::Equal)
                    || !collation_supported(expr.inputcollid)
                {
                    return None;
                }
                let args = list::<pg_sys::Node>(expr.args);
                if args.len() != 2 || (*args[1]).type_ != pg_sys::NodeTag::T_Const {
                    return None;
                }
                let column = variable(args[0], relid, relation)?;
                let constant = &*args[1].cast::<pg_sys::Const>();
                if constant.constisnull
                    || ![1005, 1007, 1016, 1009].contains(&constant.consttype.as_u32())
                {
                    return None;
                }
                let element = pg_sys::get_element_type(constant.consttype);
                let mut length = 0i16;
                let mut byval = false;
                let mut align = 0;
                pg_sys::get_typlenbyvalalign(element, &mut length, &mut byval, &mut align);
                let array = pg_sys::pg_detoast_datum(constant.constvalue.cast_mut_ptr())
                    .cast::<pg_sys::ArrayType>();
                let mut values = null_mut();
                let mut nulls = null_mut();
                let mut count = 0;
                pg_sys::deconstruct_array(
                    array,
                    element,
                    length as i32,
                    byval,
                    align,
                    &mut values,
                    &mut nulls,
                    &mut count,
                );
                if count == 0 {
                    return None;
                }
                let mut output = Vec::new();
                for index in 0..count as usize {
                    if *nulls.add(index) {
                        return None;
                    }
                    output.push(scalar(*values.add(index), element)?);
                }
                Some(Filter::In {
                    column,
                    array_type_oid: constant.consttype.as_u32(),
                    values: output,
                })
            }
            _ => None,
        }
    }
}

/// # Safety
/// Pass live planner/executor expressions and an open relation from the backend thread.
pub unsafe fn serialize(
    quals: *mut pg_sys::List,
    relid: Option<u32>,
    relation: pg_sys::Relation,
) -> Option<Vec<u8>> {
    unsafe {
        list::<pg_sys::Node>(quals)
            .into_iter()
            .filter_map(|node| expression(node, relid, relation, 0))
            .reduce(|left, right| Filter::And(Box::new(left), Box::new(right)))
            .map(|filter| filter.serialize())
    }
}

/// Derive projection from planner expressions, including every local qual.
/// Whole-row references disable projection rather than dropping required data.
/// # Safety
/// Pass live planner expressions for the specified relation and range-table index.
pub unsafe fn projection(
    target: *mut pg_sys::List,
    quals: *mut pg_sys::List,
    relid: u32,
    relation: pg_sys::Relation,
) -> Option<Vec<usize>> {
    unsafe {
        let mut used = null_mut();
        pg_sys::pull_varattnos(target.cast(), relid, &mut used);
        pg_sys::pull_varattnos(quals.cast(), relid, &mut used);
        if pg_sys::bms_is_member(-pg_sys::FirstLowInvalidHeapAttributeNumber, used) {
            pg_sys::bms_free(used);
            return None;
        }
        let desc = (*relation).rd_att;
        let mut output = Vec::new();
        let mut dense = 0;
        for (index, attr) in (*desc)
            .attrs
            .as_slice((*desc).natts as usize)
            .iter()
            .enumerate()
        {
            if attr.attisdropped {
                continue;
            }
            if pg_sys::bms_is_member(
                index as i32 + 1 - pg_sys::FirstLowInvalidHeapAttributeNumber,
                used,
            ) {
                output.push(dense);
            }
            dense += 1;
        }
        pg_sys::bms_free(used);
        Some(output)
    }
}

// Pinned pg_catalog operator OIDs from the legacy PXF allowlist.
fn operator(oid: u32) -> Option<Comparison> {
    match oid {
        94 => Some(Comparison::Equal),
        95 => Some(Comparison::Less),
        520 => Some(Comparison::Greater),
        522 => Some(Comparison::LessEqual),
        524 => Some(Comparison::GreaterEqual),
        519 => Some(Comparison::NotEqual),
        96 => Some(Comparison::Equal),
        97 => Some(Comparison::Less),
        521 => Some(Comparison::Greater),
        523 => Some(Comparison::LessEqual),
        525 => Some(Comparison::GreaterEqual),
        518 => Some(Comparison::NotEqual),
        410 => Some(Comparison::Equal),
        412 => Some(Comparison::Less),
        413 => Some(Comparison::Greater),
        414 => Some(Comparison::LessEqual),
        415 => Some(Comparison::GreaterEqual),
        411 => Some(Comparison::NotEqual),
        98 => Some(Comparison::Equal),
        664 => Some(Comparison::Less),
        666 => Some(Comparison::Greater),
        665 => Some(Comparison::LessEqual),
        667 => Some(Comparison::GreaterEqual),
        531 => Some(Comparison::NotEqual),
        1209 => Some(Comparison::Like),
        532 => Some(Comparison::Equal),
        534 => Some(Comparison::Less),
        536 => Some(Comparison::Greater),
        540 => Some(Comparison::LessEqual),
        542 => Some(Comparison::GreaterEqual),
        538 => Some(Comparison::NotEqual),
        533 => Some(Comparison::Equal),
        535 => Some(Comparison::Less),
        537 => Some(Comparison::Greater),
        541 => Some(Comparison::LessEqual),
        543 => Some(Comparison::GreaterEqual),
        539 => Some(Comparison::NotEqual),
        416 => Some(Comparison::Equal),
        418 => Some(Comparison::Less),
        419 => Some(Comparison::Greater),
        420 => Some(Comparison::LessEqual),
        430 => Some(Comparison::GreaterEqual),
        417 => Some(Comparison::NotEqual),
        15 => Some(Comparison::Equal),
        37 => Some(Comparison::Less),
        76 => Some(Comparison::Greater),
        80 => Some(Comparison::LessEqual),
        82 => Some(Comparison::GreaterEqual),
        36 => Some(Comparison::NotEqual),
        1862 => Some(Comparison::Equal),
        1864 => Some(Comparison::Less),
        1865 => Some(Comparison::Greater),
        1866 => Some(Comparison::LessEqual),
        1867 => Some(Comparison::GreaterEqual),
        1863 => Some(Comparison::NotEqual),
        1868 => Some(Comparison::Equal),
        1870 => Some(Comparison::Less),
        1871 => Some(Comparison::Greater),
        1872 => Some(Comparison::LessEqual),
        1873 => Some(Comparison::GreaterEqual),
        1869 => Some(Comparison::NotEqual),
        1093 => Some(Comparison::Equal),
        1095 => Some(Comparison::Less),
        1097 => Some(Comparison::Greater),
        1096 => Some(Comparison::LessEqual),
        1098 => Some(Comparison::GreaterEqual),
        1094 => Some(Comparison::NotEqual),
        2060 => Some(Comparison::Equal),
        2062 => Some(Comparison::Less),
        2064 => Some(Comparison::Greater),
        2063 => Some(Comparison::LessEqual),
        2065 => Some(Comparison::GreaterEqual),
        2061 => Some(Comparison::NotEqual),
        670 => Some(Comparison::Equal),
        672 => Some(Comparison::Less),
        674 => Some(Comparison::Greater),
        673 => Some(Comparison::LessEqual),
        675 => Some(Comparison::GreaterEqual),
        671 => Some(Comparison::NotEqual),
        1120 => Some(Comparison::Equal),
        1122 => Some(Comparison::Less),
        1123 => Some(Comparison::Greater),
        1124 => Some(Comparison::LessEqual),
        1125 => Some(Comparison::GreaterEqual),
        1121 => Some(Comparison::NotEqual),
        91 => Some(Comparison::Equal),
        58 => Some(Comparison::Less),
        59 => Some(Comparison::Greater),
        1694 => Some(Comparison::LessEqual),
        1695 => Some(Comparison::GreaterEqual),
        85 => Some(Comparison::NotEqual),
        1054 => Some(Comparison::Equal),
        1058 => Some(Comparison::Less),
        1060 => Some(Comparison::Greater),
        1059 => Some(Comparison::LessEqual),
        1061 => Some(Comparison::GreaterEqual),
        1057 => Some(Comparison::NotEqual),
        1752 => Some(Comparison::Equal),
        1754 => Some(Comparison::Less),
        1756 => Some(Comparison::Greater),
        1755 => Some(Comparison::LessEqual),
        1757 => Some(Comparison::GreaterEqual),
        1753 => Some(Comparison::NotEqual),
        _ => None,
    }
}

/// Executor projection can use several special Var numbers (SCAN/OUTER).
/// Collect every referenced local column; retain full rows for whole-row Vars.
/// # Safety
/// Pass live scan projection/qual expressions and the open scanned relation.
pub unsafe fn external_projection(
    target: *mut pg_sys::List,
    quals: *mut pg_sys::List,
    relation: pg_sys::Relation,
) -> Option<Vec<usize>> {
    unsafe {
        if target.is_null() {
            return None;
        }
        let flags = (pg_sys::PVC_RECURSE_AGGREGATES
            | pg_sys::PVC_RECURSE_WINDOWFUNCS
            | pg_sys::PVC_RECURSE_PLACEHOLDERS) as i32;
        let mut indexes = Vec::new();
        for expressions in [target, quals] {
            let vars = pg_sys::pull_var_clause(expressions.cast(), flags);
            for node in list::<pg_sys::Var>(vars) {
                if (*node).varattno == 0 {
                    pg_sys::list_free(vars);
                    return None;
                }
                if let Some(index) = variable(node.cast(), None, relation) {
                    indexes.push(index as usize);
                }
            }
            pg_sys::list_free(vars);
        }
        indexes.sort_unstable();
        indexes.dedup();
        Some(indexes)
    }
}
