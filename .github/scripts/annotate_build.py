"""Печатает хвост gradle.log как аннотацию ошибки.

Зачем: в ряде окружений полный лог запуска недоступен, а аннотации — доступны.
Скрипт ничего не собирает и не меняет, только читает $RUNNER_TEMP/gradle.log.
"""
import os
import sys

path = os.path.join(os.environ.get("RUNNER_TEMP", "."), "gradle.log")
try:
    text = open(path, encoding="utf-8", errors="replace").read().splitlines()
except OSError as exc:
    print(f"::error title=Gradle::{exc}")
    sys.exit(0)

markers = ("e: ", "error:", "FAILURE", "> Task", "* What went wrong", "Caused by")
keep = [line for line in text if line.startswith(markers)]
if not keep:
    keep = text[-120:]

msg = "\n".join(keep[-200:])[:60000]
escaped = msg.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
print("::error title=Gradle::" + escaped)
