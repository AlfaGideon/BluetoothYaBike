"""Печатает упавшие юнит-тесты аннотацией (логи ранера бывают недоступны)."""
import glob
import os
import xml.etree.ElementTree as ET

paths = glob.glob("app/build/test-results/**/*.xml", recursive=True)
if not paths:
    print("::error title=Tests::нет отчётов test-results (сборка упала раньше тестов?)")
    raise SystemExit(0)

lines = []
for path in paths:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        lines.append(f"{os.path.basename(path)}: не разобрать XML ({exc})")
        continue
    for case in root.iter("testcase"):
        for tag in ("failure", "error"):
            node = case.find(tag)
            if node is None:
                continue
            message = (node.get("message") or node.text or "").strip().splitlines()
            head = message[0] if message else tag
            lines.append(f"{case.get('classname')}.{case.get('name')}: {head[:300]}")

if not lines:
    print("::error title=Tests::тесты не найдены в отчётах")
else:
    print("::error title=Tests::" + "%0A".join(lines[:50]).replace("%", "%25").replace("\n", "%0A"))
