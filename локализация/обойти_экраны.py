"""Обход экранов приложения на эмуляторе: тапаем вкладки, снимаем UI и тексты.

Использование:
    python обойти_экраны.py <язык> <вкладка1,вкладка2,...>

Для каждой вкладки: находим её в снимке UI (при необходимости прокручиваем строку
вкладок), тапаем, делаем снимки UI и экрана сверху и после прокрутки вниз.
На вкладке «Журнал» дополнительно раскрывается свёрнутый техжурнал.
Печатает тексты, в которых осталась кириллица (кроме разрешённых имён данных).
"""
import io
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
СЕРИЯ = "emulator-5554"
ЗДЕСЬ = os.path.dirname(os.path.abspath(__file__))
ВЫВОД = os.environ.get("СНИМКИ") or os.path.join(ЗДЕСЬ, "снимки")
ШИРИНА, ВЫСОТА = 1080, 2220
ПОЛОСА_ВКЛАДОК = (500, 650)
KYR = re.compile("[А-Яа-яЁёІіЇїЄєҐґ]")
# Данные, которые остаются русскими на любом языке: имена сайтов/портов/служб,
# имена узлов и хостов, пути, версии ядра/ОС и т. п.
РАЗРЕШЕНО = (
    "serverastus",
    "mysqld",
    "systemd",
    "fail2ban",
    "dbus-daemon",
    "redis-server",
    "gunicorn",
    "python",
    "sshd",
    "Ubuntu",
    "generic",
    "astuslab",
)

if not os.path.isdir(ВЫВОД):
    os.makedirs(ВЫВОД)


def adb(*аргументы, двоичный=False):
    итог = subprocess.run([ADB, "-s", СЕРИЯ] + list(аргументы), capture_output=True)
    if двоичный:
        return итог.stdout
    return итог.stdout.decode("utf-8", "replace")


def границы(строка):
    числа = [int(ч) for ч in re.findall(r"\d+", строка)]
    return числа[0], числа[1], числа[2], числа[3]


def найти_узел(документ, подпись):
    корень = ET.fromstring(документ)
    найденные = []
    for узел in корень.iter():
        текст = (узел.get("text") or "").strip()
        desc = (узел.get("content-desc") or "").strip()
        if текст == подпись or desc == подпись:
            найденные.append(узел)
    for узел in найденные:
        _, y1, _, y2 = границы(узел.get("bounds"))
        if ПОЛОСА_ВКЛАДОК[0] <= (y1 + y2) // 2 <= ПОЛОСА_ВКЛАДОК[1]:
            return узел
    return найденные[0] if найденные else None


def нажать(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def прокрутить_вкладки(влево=True):
    if влево:
        adb("shell", "input", "swipe", "950", "570", "150", "570", "300")
    else:
        adb("shell", "input", "swipe", "150", "570", "950", "570", "300")


def снять(имя):
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    путь_xml = os.path.join(ВЫВОД, имя + ".xml")
    io.open(путь_xml, "w", encoding="utf-8").write(xml)
    путь_png = os.path.join(ВЫВОД, имя + ".png")
    данные = adb("exec-out", "screencap", "-p", двоичный=True)
    open(путь_png, "wb").write(данные)
    return xml


def тексты(xml):
    корень = ET.fromstring(xml)
    итог = []
    for узел in корень.iter():
        for атрибут in ("text", "content-desc"):
            значение = (узел.get(атрибут) or "").strip()
            if значение:
                итог.append(значение)
    return итог


def подозрительные(строки):
    итог = []
    for с in строки:
        if not KYR.search(с):
            continue
        if any(разрешено in с for разрешено in РАЗРЕШЕНО):
            continue
        итог.append(с)
    return итог


def раскрыть_техжурнал(язык, вкладка, xml):
    """Раскрывает техжурнал на вкладке «Журнал»: свёрнутый, он в снимок не попадает."""
    for подпись in ("Показать", "Показати", "Show"):
        узел = найти_узел(xml, подпись)
        if узел is None:
            continue
        x1, y1, x2, y2 = границы(узел.get("bounds"))
        нажать((x1 + x2) // 2, (y1 + y2) // 2)
        adb("shell", "sleep", "2")
        return снять("%s_%s_1" % (язык, вкладка))
    return xml


def main():
    язык = sys.argv[1]
    вкладки = sys.argv[2].split(",")
    for вкладка in вкладки:
        xml = снять("%s_старт" % язык)
        for шаг in range(6):
            if найти_узел(xml, вкладка) is not None:
                break
            прокрутить_вкладки(влево=(шаг % 2 == 0))
            xml = снять("%s_поиск" % язык)
        узел = найти_узел(xml, вкладка)
        if узел is None:
            print("!! вкладка не найдена: %s" % вкладка)
            continue
        x1, y1, x2, y2 = границы(узел.get("bounds"))
        нажать((max(x1, 8) + min(x2, ШИРИНА - 8)) // 2, (y1 + y2) // 2)
        adb("shell", "sleep", "3")
        все = []
        xml = снять("%s_%s_1" % (язык, вкладка))
        if вкладка in ("Журнал", "Journal"):
            xml = раскрыть_техжурнал(язык, вкладка, xml)
        все += тексты(xml)
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "400")
        adb("shell", "sleep", "1")
        xml = снять("%s_%s_2" % (язык, вкладка))
        все += тексты(xml)
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "400")
        adb("shell", "sleep", "1")
        xml = снять("%s_%s_3" % (язык, вкладка))
        все += тексты(xml)
        adb("shell", "input", "swipe", "540", "700", "540", "1900", "400")
        adb("shell", "input", "swipe", "540", "700", "540", "1900", "400")
        io.open(os.path.join(ВЫВОД, "%s_%s_тексты.txt" % (язык, вкладка)), "w",
                encoding="utf-8").write("\n".join(все))
        плохие = подозрительные(все)
        print("=== %s (%s): текстов %d, с кириллицей %d" % (вкладка, язык, len(все), len(плохие)))
        for с in плохие:
            print("    " + с)


if __name__ == "__main__":
    main()
