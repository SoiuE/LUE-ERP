from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, tostring
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[1]
OUT_DIRS = [ROOT / "frontend" / "public" / "templates", ROOT / "backend" / "src" / "main" / "resources" / "static" / "templates"]
NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
NS_PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"


def col_name(index):
    result = ""
    while index:
        index, remainder = divmod(index - 1, 26)
        result = chr(65 + remainder) + result
    return result


def worksheet(rows, widths=None, validations=None, filter_range=None, frozen_rows=0):
    root = Element(f"{{{NS_MAIN}}}worksheet")
    if frozen_rows:
        views = SubElement(root, f"{{{NS_MAIN}}}sheetViews")
        view = SubElement(views, f"{{{NS_MAIN}}}sheetView", {"workbookViewId": "0"})
        SubElement(view, f"{{{NS_MAIN}}}pane", {"ySplit": str(frozen_rows), "topLeftCell": f"A{frozen_rows + 1}", "activePane": "bottomLeft", "state": "frozen"})
    if widths:
        cols = SubElement(root, f"{{{NS_MAIN}}}cols")
        for index, width in enumerate(widths, 1):
            SubElement(cols, f"{{{NS_MAIN}}}col", {"min": str(index), "max": str(index), "width": str(width), "customWidth": "1"})
    data = SubElement(root, f"{{{NS_MAIN}}}sheetData")
    for row_index, values in enumerate(rows, 1):
        row = SubElement(data, f"{{{NS_MAIN}}}row", {"r": str(row_index)})
        for col_index, value in enumerate(values, 1):
            cell = SubElement(row, f"{{{NS_MAIN}}}c", {"r": f"{col_name(col_index)}{row_index}", "t": "inlineStr"})
            inline = SubElement(cell, f"{{{NS_MAIN}}}is")
            text = SubElement(inline, f"{{{NS_MAIN}}}t")
            text.text = str(value)
    if filter_range:
        SubElement(root, f"{{{NS_MAIN}}}autoFilter", {"ref": filter_range})
    if validations:
        data_validations = SubElement(root, f"{{{NS_MAIN}}}dataValidations", {"count": str(len(validations))})
        for cell_range, options, prompt in validations:
            validation = SubElement(data_validations, f"{{{NS_MAIN}}}dataValidation", {
                "type": "list", "allowBlank": "1", "showInputMessage": "1", "showErrorMessage": "1",
                "promptTitle": "固定字段选项", "prompt": prompt, "errorTitle": "请选择列表值", "error": "请从下拉选项中选择，不要手工修改固定字段。", "sqref": cell_range,
            })
            formula = SubElement(validation, f"{{{NS_MAIN}}}formula1")
            formula.text = '"' + ",".join(options) + '"'
    return tostring(root, encoding="utf-8", xml_declaration=True)


def build(filename, headers, required_notes, example, fixed_options):
    instructions = [("字段名", "必填", "说明")]
    for name in headers:
        note = fixed_options.get(name, (None, "可选；平台导出包含此字段时建议保留原值"))[1]
        note = required_notes.get(name, note)
        instructions.append((name, "是" if name in required_notes else "否", note))
    last_column = col_name(len(headers))
    validations = []
    for field, (options, note) in fixed_options.items():
        if field in headers:
            column = col_name(headers.index(field) + 1)
            validations.append((f"{column}2:{column}1001", options, note))
    example_rows = [headers, example]
    files = {
        "[Content_Types].xml": f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="{NS_CT}"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>''',
        "_rels/.rels": f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="{NS_PKG}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>''',
        "xl/workbook.xml": f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="{NS_MAIN}" xmlns:r="{NS_REL}"><sheets><sheet name="平台数据" sheetId="1" r:id="rId1"/><sheet name="填写示例" sheetId="2" r:id="rId2"/><sheet name="字段说明" sheetId="3" r:id="rId3"/></sheets></workbook>''',
        "xl/_rels/workbook.xml.rels": f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="{NS_PKG}"><Relationship Id="rId1" Type="{NS_REL}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="{NS_REL}/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="{NS_REL}/worksheet" Target="worksheets/sheet3.xml"/></Relationships>''',
        "xl/worksheets/sheet1.xml": worksheet([headers], [max(16, min(36, len(header) * 2 + 4)) for header in headers], validations, f"A1:{last_column}1001", 1).decode("utf-8"),
        "xl/worksheets/sheet2.xml": worksheet(example_rows, [max(16, min(36, len(header) * 2 + 4)) for header in headers], filter_range=f"A1:{last_column}2", frozen_rows=1).decode("utf-8"),
        "xl/worksheets/sheet3.xml": worksheet(instructions, [30, 10, 72], filter_range=f"A1:C{len(instructions)}", frozen_rows=1).decode("utf-8"),
    }
    for out_dir in OUT_DIRS:
        out_dir.mkdir(parents=True, exist_ok=True)
        with ZipFile(out_dir / filename, "w", ZIP_DEFLATED) as workbook:
            for name, content in files.items():
                workbook.writestr(name, content)


common = ["平台", "店铺标识/账号标识", "单据类型", "来源平台单据ID", "明细/订单行ID", "平台SKU标识", "平台SKU", "数量", "订单/备货状态", "组包批次ID", "包裹/箱号", "标签编号", "标签文件名", "预约编号", "取件开始时间", "取件结束时间", "取件时间段", "平台数据更新时间"]
build(
    "aliexpress_jit_fulfillment_template.xlsx",
    common + ["订单日期", "订单模式"],
    {
        "单据类型": "固定填写 JIT_ORDER；本模板仅用于 JIT 客户订单",
        "店铺标识/账号标识": "填写 ERP 店铺档案中的店铺标识或账号标识",
        "来源平台单据ID": "JIT 客户订单的平台订单 ID",
        "平台SKU": "用于匹配店铺 SKU 映射，必须与平台导出值一致",
        "数量": "订单明细数量，必须大于 0",
        "订单日期": "JIT 订单日期，填写 YYYY-MM-DD 格式",
    },
    ["ALIEXPRESS", "示例店铺", "JIT_ORDER", "SAMPLE-ORDER-001", "LINE-001", "SAMPLE-PLATFORM-SKU", "SKU-DEMO-001", "2", "已组包", "BATCH-001", "PKG-001", "LABEL-001", "label-001.pdf", "APPT-001", "2026-10-16 09:00:00", "2026-10-16 12:00:00", "09:00-12:00", "2026-10-15 10:00:00", "2026-10-15", "JIT"],
    {
        "平台": (["ALIEXPRESS"], "ALIEXPRESS 为当前支持的平台固定值"),
        "单据类型": (["JIT_ORDER"], "本模板仅用于 JIT 客户订单，固定选择 JIT_ORDER"),
        "订单模式": (["JIT"], "JIT 模板订单模式固定选择 JIT"),
    },
)
build(
    "aliexpress_warehouse_fulfillment_template.xlsx",
    common + ["目标平台仓"],
    {
        "单据类型": "固定填写 PLATFORM_WAREHOUSE_STOCKING；本模板仅用于仓发入平台仓备货单",
        "店铺标识/账号标识": "填写 ERP 店铺档案中的店铺标识或账号标识",
        "来源平台单据ID": "速卖通仓发入平台仓备货单 ID",
        "平台SKU": "用于匹配店铺 SKU 映射，必须与平台导出值一致",
        "数量": "备货明细数量，必须大于 0",
        "目标平台仓": "备货单的目标平台仓名称或代码",
    },
    ["ALIEXPRESS", "示例店铺", "PLATFORM_WAREHOUSE_STOCKING", "SAMPLE-STOCK-001", "STOCK-LINE-001", "PLATFORM-SKU-002", "SKU-DEMO-002", "10", "待发货", "BATCH-002", "CARTON-001", "LABEL-002", "label-002.pdf", "APPT-002", "2026-10-17 09:00:00", "2026-10-17 12:00:00", "09:00-12:00", "2026-10-15 10:00:00", "速卖通平台仓示例"],
    {
        "平台": (["ALIEXPRESS"], "ALIEXPRESS 为当前支持的平台固定值"),
        "单据类型": (["PLATFORM_WAREHOUSE_STOCKING"], "本模板仅用于仓发入平台仓备货单，固定选择 PLATFORM_WAREHOUSE_STOCKING"),
    },
)
