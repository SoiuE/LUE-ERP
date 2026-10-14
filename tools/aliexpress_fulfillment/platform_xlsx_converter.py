#!/usr/bin/env python3
"""Convert AliExpress JIT / platform-warehouse exports to ERP import workbooks.

The tool only reads and writes local .xlsx files. It does not connect to AliExpress.
"""
from __future__ import annotations

import argparse
import re
import sys
from datetime import date, datetime
from pathlib import Path
from typing import Any

from openpyxl import Workbook, load_workbook


FIELDS = [
    "platform", "shop_identifier", "document_type", "source_document_id",
    "source_line_id", "platform_sku_id", "platform_sku", "quantity",
    "source_status", "group_batch_id", "package_no", "label_no",
    "label_filename", "appointment_no", "pickup_start", "pickup_end",
    "updated_at", "order_date", "order_mode", "target_platform_warehouse", "converter_error",
]
LABELS = {
    "platform": "平台", "shop_identifier": "店铺标识/账号标识", "document_type": "单据类型",
    "source_document_id": "来源平台单据ID", "source_line_id": "明细/订单行ID",
    "platform_sku_id": "平台SKU标识", "platform_sku": "平台SKU", "quantity": "数量",
    "source_status": "订单/备货状态", "group_batch_id": "组包批次ID", "package_no": "包裹/箱号",
    "label_no": "标签编号", "label_filename": "标签文件名", "appointment_no": "预约编号",
    "pickup_start": "取件开始时间", "pickup_end": "取件结束时间", "updated_at": "平台数据更新时间",
    "order_date": "订单日期", "order_mode": "订单模式", "target_platform_warehouse": "目标平台仓",
    "converter_error": "转换校验错误",
}

# Normalized header aliases accommodate Chinese and English platform export variants.
ALIASES: dict[str, set[str]] = {
    "platform": {"平台", "platform"},
    "shop_identifier": {"店铺标识", "账号标识", "店铺账号", "店铺", "店铺名称", "account", "selleraccount", "shopidentifier"},
    "source_document_id": {"来源平台单据id", "平台单据id", "订单id", "客户订单id", "平台客户订单id", "仓发单id", "平台仓发单id", "入平台仓单据id", "sourceid", "orderid", "shipmentid", "inboundid", "platform_order_id", "platform_replenishment_id"},
    "source_line_id": {"明细id", "订单行id", "备货明细id", "平台订单行id", "平台明细id", "lineid", "orderlineid", "itemid", "platform_order_line_id", "platform_replenishment_line_id"},
    "platform_sku_id": {"平台skuid", "平台商品id", "平台sku标识", "明细平台sku标识", "productid", "skuid"},
    "platform_sku": {"平台sku", "商品sku", "sku", "seller sku", "seller_sku"},
    "quantity": {"数量", "订单数量", "备货数量", "商品数量", "qty", "quantity"},
    "source_status": {"订单状态", "备货状态", "平台状态", "status"},
    "group_batch_id": {"组包批次id", "组包批次号", "组包id", "batchid", "packbatchid"},
    "package_no": {"包裹号", "包裹编号", "箱号", "包裹箱号", "package", "packageno", "cartonno"},
    "label_no": {"标签编号", "面单号", "labelid", "labelno"},
    "label_filename": {"标签文件名", "标签文件", "面单文件名", "label", "labelfilename"},
    "appointment_no": {"预约编号", "预约号", "取件预约号", "appointmentid", "appointmentno"},
    "pickup_start": {"取件开始时间", "取件开始", "预约开始时间", "pickupstart", "pickupstarttime"},
    "pickup_end": {"取件结束时间", "取件结束", "预约结束时间", "pickupend", "pickupendtime"},
    "pickup_window": {"取件时间段", "预约时间段", "取件时段", "pickupwindow", "appointmentwindow"},
    "updated_at": {"数据更新时间", "平台更新时间", "更新时间", "updatedat", "lastupdated"},
    "order_date": {"订单日期", "下单日期", "orderdate", "createdat"},
    "order_mode": {"订单模式", "ordermode"},
    "target_platform_warehouse": {"目标平台仓", "平台仓", "仓库名称", "仓库代码", "targetwarehouse", "warehouse"},
}


def norm(value: Any) -> str:
    return re.sub(r"[\s_\-（）()【】\[\]：:./\\]+", "", str(value or "").strip().lower())


ALIAS_INDEX = {norm(alias): key for key, aliases in ALIASES.items() for alias in aliases}
ALIAS_INDEX.update({norm(key): key for key in FIELDS})


def cell_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (datetime, date)):
        return value.isoformat(sep=" ") if isinstance(value, datetime) else value.isoformat()
    return str(value).strip()


def convert(kind: str, source: Path, destination: Path) -> tuple[int, int]:
    wb = load_workbook(source, read_only=True, data_only=True)
    ws = wb.active
    iterator = ws.iter_rows(values_only=True)
    header = next(iterator, None)
    if not header:
        raise ValueError("源文件没有表头行")
    columns: dict[str, int] = {}
    for idx, value in enumerate(header):
        field = ALIAS_INDEX.get(norm(value))
        if field:
            if field in columns:
                raise ValueError(f"多个表头映射到同一字段：{LABELS[field]}")
            columns[field] = idx
    required = ["shop_identifier", "source_document_id", "platform_sku", "quantity"]
    missing = [LABELS[key] for key in required if key not in columns]
    if missing:
        raise ValueError("缺少必需表头：" + "、".join(missing))

    output_rows: list[dict[str, Any]] = []
    errors: list[list[Any]] = []
    raw_rows: list[list[Any]] = []
    seen: dict[tuple[str, ...], int] = {}
    doc_type = "JIT_ORDER" if kind == "jit" else "PLATFORM_WAREHOUSE_STOCKING"
    for row_no, values in enumerate(iterator, start=2):
        if not values or all(value is None or str(value).strip() == "" for value in values):
            continue
        raw = {str(header[i] or f"列{i+1}"): cell_text(values[i] if i < len(values) else None) for i in range(len(header))}
        raw_rows.append([row_no, *[cell_text(value) for value in values[:len(header)]]])
        record = {key: cell_text(values[idx] if idx < len(values) else None) for key, idx in columns.items()}
        record["platform"] = record.get("platform") or "ALIEXPRESS"
        record["document_type"] = doc_type
        record["order_mode"] = "JIT" if kind == "jit" else "WAREHOUSE"
        problems: list[str] = []
        if not record.get("shop_identifier"):
            problems.append("店铺/账号标识不能为空")
        if not record.get("source_document_id"):
            problems.append("缺少稳定的平台单据 ID，不能安全建立单据关联")
        if not record.get("platform_sku"):
            problems.append("平台 SKU 不能为空")
        try:
            qty = float(record.get("quantity", ""))
            if qty <= 0:
                raise ValueError
            record["quantity"] = f"{qty:.4f}".rstrip("0").rstrip(".")
        except (TypeError, ValueError):
            problems.append("数量必须是大于 0 的数字")
        if kind == "jit" and not record.get("order_date"):
            problems.append("JIT 订单日期不能为空")
        if kind == "warehouse" and not record.get("target_platform_warehouse"):
            problems.append("目标平台仓不能为空")
        pickup_window = record.get("pickup_window", "")
        if pickup_window and (not record.get("pickup_start") or not record.get("pickup_end")):
            parts = re.split(r"\s*(?:至|到|~|～|—|-)\s*", pickup_window, maxsplit=1)
            if len(parts) == 2:
                record["pickup_start"] = record.get("pickup_start") or parts[0]
                record["pickup_end"] = record.get("pickup_end") or parts[1]
        line_id = record.get("source_line_id", "")
        # Package/group keys distinguish legitimate per-package splits of one platform line.
        # Without a line ID, parent+package/group+SKU is accepted only when unique in the file.
        identity = (record.get("source_document_id", ""), line_id, record.get("group_batch_id", ""), record.get("package_no", ""), record.get("platform_sku", ""))
        if identity in seen:
            problems.append(f"与 Excel 第 {seen[identity]} 行缺少明细 ID 且组合键重复，需人工核对")
        else:
            seen[identity] = row_no
        if kind == "jit":
            mode = record.get("order_mode", "JIT").upper()
            if mode != "JIT":
                problems.append("JIT 组包导入的订单模式必须为 JIT")
            record["order_mode"] = "JIT"
        if problems:
            errors.append([row_no, "；".join(problems), str(raw)])
        record["converter_error"] = "；".join(problems)
        output_rows.append(record)
    wb.close()

    out = Workbook()
    data = out.active
    data.title = "ERP导入"
    data.append(FIELDS)
    for record in output_rows:
        data.append([record.get(key, "") for key in FIELDS])
    data.freeze_panes = "A2"
    data.auto_filter.ref = data.dimensions
    for column in data.columns:
        letter = column[0].column_letter
        data.column_dimensions[letter].width = min(34, max(14, max(len(str(c.value or "")) for c in column) + 2))
    error_sheet = out.create_sheet("转换错误")
    error_sheet.append(["源文件行号", "错误原因", "原始行数据"])
    for row in errors:
        error_sheet.append(row)
    raw_sheet = out.create_sheet("平台原始数据")
    raw_sheet.append(["源文件行号", *[str(value or f"列{i+1}") for i, value in enumerate(header)]])
    for row in raw_rows:
        raw_sheet.append(row)
    summary = out.create_sheet("转换结果")
    summary.append(["项目", "结果"])
    summary.append(["单据类型", "JIT 客户订单" if kind == "jit" else "速卖通仓发入平台仓备货"])
    summary.append(["源文件", source.name])
    summary.append(["转换行数", len(output_rows)])
    summary.append(["需要人工核对/字段错误", len(errors)])
    summary.append(["说明", "ERP 导入前请在“ERP导入”工作表复核；转换错误不会自动丢弃原始行。"])
    destination.parent.mkdir(parents=True, exist_ok=True)
    out.save(destination)
    return len(output_rows), len(errors)


def main() -> int:
    parser = argparse.ArgumentParser(description="将速卖通导出的 JIT/仓发备货 .xlsx 转为 ERP 导入表")
    parser.add_argument("--type", choices=("jit", "warehouse"), required=True, help="jit=客户 JIT 订单；warehouse=仓发入平台仓备货单")
    parser.add_argument("--input", type=Path, required=True, help="平台导出的 .xlsx 源文件")
    parser.add_argument("--output", type=Path, required=True, help="ERP 导入用 .xlsx 输出路径")
    args = parser.parse_args()
    if args.input.suffix.lower() != ".xlsx" or args.output.suffix.lower() != ".xlsx":
        parser.error("输入和输出文件必须使用 .xlsx")
    if not args.input.is_file():
        parser.error(f"找不到输入文件：{args.input}")
    try:
        rows, errors = convert(args.type, args.input, args.output)
    except Exception as exc:  # CLI reports actionable row/template errors.
        print(f"转换失败：{exc}", file=sys.stderr)
        return 2
    print(f"转换完成：{args.output}；记录 {rows} 行，错误/人工核对 {errors} 行。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
