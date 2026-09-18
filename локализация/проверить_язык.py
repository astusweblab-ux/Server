"""Проверка локализации на устройстве: снимок UI, тексты, поиск кириллицы.

Использование:
    python проверить_язык.py <файл ui.xml> [язык]
Если язык uk/en — печатает все тексты и отдельно подозрительные (кириллица),
не считая кириллицу в именах узлов из allow-списка (данные сервера).
"""
import io
import re
import sys
import xml.etree.ElementTree as ET

KYR = re.compile("[А-Яа-яЁёІіЇїЄєҐґ]")


def тексты(путь):
    итог = []
    with io.open(путь, encoding="utf-8") as ф:
        текст = ф.read()
    корень = ET.fromstring(текст)
    for узел in корень.iter():
        for атрибут in ("text", "content-desc"):
            значение = узел.get(атрибут) or ""
            if значение.strip():
                итог.append((узел.get("class", "").split(".")[-1], значение))
    return итог


def main():
    путь = sys.argv[1]
    язык = sys.argv[2] if len(sys.argv) > 2 else ""
    строки = тексты(путь)
    print("всего текстов: %d" % len(строки))
    for класс, значение in строки:
        метка = "КИРИЛЛИЦА " if KYR.search(значение) else ""
        print("%s%s | %s" % (метка, класс, значение))


if __name__ == "__main__":
    main()
