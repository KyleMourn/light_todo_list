"""静态自检：XML 合法性、Kotlin 括号配平、资源引用存在性。仅本地校验，不替代编译。"""
import re
import sys
from pathlib import Path

from lxml import etree

ROOT = Path(__file__).resolve().parent.parent
errors = []
checked = {"xml": 0, "kt": 0}


def check_xml():
    for path in sorted(ROOT.rglob("*.xml")):
        if ".gradle" in path.parts:
            continue
        checked["xml"] += 1
        try:
            etree.parse(str(path))
        except Exception as exc:  # noqa: BLE001
            errors.append(f"XML 解析失败 {path.relative_to(ROOT)}: {exc}")


def strip_kotlin(src: str) -> str:
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    src = re.sub(r"'(?:\\.|[^'\\\n])'", "''", src)
    src = re.sub(r"/\*(?:.|\n)*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def check_kotlin():
    opener = {"{": "}", "(": ")", "[": "]"}
    closer = {v: k for k, v in opener.items()}
    for path in sorted(ROOT.rglob("*.kt")):
        checked["kt"] += 1
        raw = path.read_text(encoding="utf-8")
        src = strip_kotlin(raw)
        stack = []
        line = 1
        for ch in src:
            if ch == "\n":
                line += 1
            elif ch in opener:
                stack.append((ch, line))
            elif ch in closer:
                if not stack or stack[-1][0] != closer[ch]:
                    errors.append(f"括号不匹配 {path.relative_to(ROOT)}:{line} 遇到 {ch}")
                    break
                stack.pop()
        else:
            if stack:
                errors.append(
                    f"括号未闭合 {path.relative_to(ROOT)}：{len(stack)} 个，最后在行 {stack[-1][1]}"
                )


def check_resources():
    res = ROOT / "app" / "src" / "main" / "res"
    layout = {p.stem for p in (res / "layout").glob("*.xml")}
    values = {}
    for p in (res / "values").glob("*.xml"):
        tree = etree.parse(str(p))
        for node in tree.getroot():
            name = node.get("name")
            if name:
                values.setdefault(p.stem, set()).add(name)
    drawable = {p.stem for p in (res / "drawable").glob("*")}
    xmlres = {p.stem for p in (res / "xml").glob("*.xml")}

    required = {
        "layout": layout,
        "color": values.get("colors", set()),
        "string": values.get("strings", set()),
        "drawable": drawable,
        "xml": xmlres,
    }
    for kind, names in required.items():
        if not names:
            errors.append(f"资源目录为空：{kind}")

    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    for kind, names in required.items():
        for ref in re.findall(rf"@(?:{kind})/([A-Za-z0-9_]+)", manifest):
            if ref not in names:
                errors.append(f"Manifest 引用了不存在的 @{kind}/{ref}")

    for kt in (ROOT / "app/src/main/java").rglob("*.kt"):
        src = kt.read_text(encoding="utf-8")
        for kind, names in required.items():
            if kind not in {"layout", "color", "string", "drawable", "xml"}:
                continue
            for ref in re.findall(rf"\bR\.{kind}\.([A-Za-z0-9_]+)", src):
                if ref not in names:
                    errors.append(f"{kt.relative_to(ROOT)} 引用了不存在的 R.{kind}.{ref}")

    # 关键文件存在性
    must = [
        "app/src/main/java/com/lighttodo/app/MainActivity.kt",
        "app/src/main/java/com/lighttodo/app/data/TodoRepository.kt",
        "app/src/main/java/com/lighttodo/app/widget/TodoWidget.kt",
        "app/src/main/java/com/lighttodo/app/widget/TodoWidgetReceiver.kt",
        "app/src/main/java/com/lighttodo/app/widget/WidgetActions.kt",
        "app/src/main/res/xml/todo_widget_info.xml",
    ]
    for rel in must:
        if not (ROOT / rel).exists():
            errors.append(f"缺少文件 {rel}")


def check_manifest_widget():
    tree = etree.parse(str(ROOT / "app/src/main/AndroidManifest.xml"))
    ns = {"a": "http://schemas.android.com/apk/res/android"}
    receivers = tree.findall(".//receiver", ns)
    if not receivers:
        errors.append("Manifest 未注册 appwidget receiver")
        return
    found_provider = False
    for r in receivers:
        if r.find("meta-data", ns) is not None:
            found_provider = True
    if not found_provider:
        errors.append("receiver 缺少 appwidget.provider meta-data")
    intents = [e.get("{http://schemas.android.com/apk/res/android}name") for e in tree.findall(".//action", ns)]
    if "android.appwidget.action.APPWIDGET_UPDATE" not in intents:
        errors.append("缺少 APPWIDGET_UPDATE intent-filter")


check_xml()
check_kotlin()
check_resources()
check_manifest_widget()

print(f"XML 文件: {checked['xml']} 个，Kotlin 文件: {checked['kt']} 个")
if errors:
    print("发现问题：")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("静态自检通过（注意：这不等于编译通过）")
