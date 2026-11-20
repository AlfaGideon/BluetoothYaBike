#!/usr/bin/env python3
"""
Разбор Android HCI snoop log (btsnoop) для поиска BLE-обмена приложения Яндекс Про
с модулем велосипеда.

Зачем: официальное приложение разблокирует байк по Bluetooth без интернета, но формат
кадров неизвестен. Снимаем трафик один раз, этим скриптом вытаскиваем все ATT-записи
(Write Command/Request, Notify, Indicate) — и в приложении BluetoothYaBike повторяем
найденные кадры.

Как включить запись на телефоне:
  1. Настройки -> О телефоне -> 7 раз тапнуть «Номер сборки» (режим разработчика).
  2. Для разработчиков -> «Включить журнал Bluetooth HCI» (Enable Bluetooth HCI snoop log).
  3. Перезагрузить Bluetooth (выключить/включить) — лог начнёт писаться заново.
  4. Открыть Яндекс Про, разблокировать байк по Bluetooth (интернет можно отключить,
     чтобы в лог не попал лишний шум).
  5. Забрать файл:
       adb bugreport bugreport.zip     -> внутри .../btsnoop_hci.log
       или adb pull /sdcard/btsnoop_hci.log
  6. python3 parse_btsnoop.py btsnoop_hci.log --mac AA:BB:CC:DD:EE:FF

Скрипт не требует сторонних пакетов. Проверка: python3 parse_btsnoop.py --selftest
"""

from __future__ import annotations

import argparse
import struct
import sys
from dataclasses import dataclass, field

BTSNOOP_MAGIC = b"btsnoop\x00"


@dataclass
class Packet:
    ts_us: int
    flags: int
    data: bytes


def sniff_datalink(raw: bytes) -> int:
    """Эвристика для btsnoop с неизвестным datalink: 1002 (Android) или 1001 (H4 UART).

    Заголовок официального Android-лога может содержать 1002, но записи при этом
    храниться как H4 (с префиксом типа) — поэтому тип пакета определяем по данным.
    """
    try:
        value = struct.unpack(">I", raw[12:16])[0]
        return value if value in (1001, 1002) else 1002
    except struct.error:
        return 1002


def read_btsnoop(path: str):
    with open(path, "rb") as f:
        raw = f.read()
    if not raw.startswith(BTSNOOP_MAGIC):
        raise SystemExit("Это не btsnoop: нет магии 'btsnoop\\0'. "
                         "Файл может быть сжатым (.gz) или это pcap — распакуйте/конвертируйте.")
    if len(raw) < 16:
        raise SystemExit("Файл btsnoop повреждён: нет заголовка")
    # version(4) + datalink_type(4) = 16 байт вместе с магией
    datalink = struct.unpack(">I", raw[12:16])[0]
    if datalink not in (1002, 1001):
        datalink = sniff_datalink(raw)
        print(f"# внимание: неизвестный datalink_type, считаю {datalink}", file=sys.stderr)
    offset = 16
    packets = []
    while offset + 24 <= len(raw):
        orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIQ", raw[offset:offset + 24])
        offset += 24
        data = raw[offset:offset + incl_len]
        offset += incl_len
        packets.append(Packet(ts, flags, data))
    return packets, datalink


def packet_type_and_payload(d: bytes, flags: int, datalink: int = 1002):
    """Возвращает (тип HCI-пакета в нумерации H4, полезная нагрузка).

    1002 = HCI UART (H4), так пишет Android: каждая запись начинается байтом типа
    (0x01 command, 0x02 ACL, 0x03 SCO, 0x04 event). Для остальных datalink тип
    восстанавливается из flags: бит 1 = 0 -> ACL, 1 -> command (бит 0 = 0)
    или event (бит 0 = 1).
    """
    if not d:
        return 0, d
    if datalink == 1002 and d[0] in (1, 2, 3, 4):
        return d[0], d[1:]
    is_cmd_or_event = bool(flags & 0x02)
    is_received = bool(flags & 0x01)
    if not is_cmd_or_event:
        return 2, d
    return (4 if is_received else 1), d


def packets_to_hci(packets, datalink: int = 1002):
    """btsnoop-запись хранит HCI-пакеты либо в H4 (с префиксом типа), либо без него.

    В Android (datalink 1002) тип пакета закодирован в flags: младший бит = направление,
    а тип — в flags >> 1 (0=command, 1=ACL, 2=SCO, 3=event, 4=ISO). Проверяем оба варианта.
    """
    out = []
    for p in packets:
        if not p.data:
            continue
        pkt_type, payload = packet_type_and_payload(p.data, p.flags, datalink)
        if pkt_type > 5:
            continue
        out.append((p.ts_us, pkt_type, payload))
    return out


def iter_l2cap_from_acl(payload: bytes):
    """Из HCI ACL-пакета достаёт фрагменты L2CAP. Возвращает (handle, pb_flag, l2cap_bytes)."""
    if len(payload) < 5:
        return
    handle_flags = struct.unpack("<H", payload[0:2])[0]
    handle = handle_flags & 0x0FFF
    pb = (handle_flags >> 12) & 0x3
    dlen = struct.unpack("<H", payload[2:4])[0]
    body = payload[4:4 + dlen]
    yield handle, pb, body


# opcode -> (название, есть ли в PDU handle характеристики)
ATT_OPCODES = {
    0x12: ("Write Command", True),
    0x52: ("Write Request", True),
    0x1B: ("Notification", True),
    0x1D: ("Indication", True),
    0x0A: ("Read Request", True),
    0x0B: ("Read Response", False),
    0x1E: ("Confirm", False),
    0x08: ("Read By Type Request", False),
    0x09: ("Read By Type Response", False),
}


class L2capReassembler:
    """Склеивает L2CAP-фрагменты по ACL handle. Достаточно для BLE-трафика (CID 4 = ATT)."""

    def __init__(self):
        self.buffers: dict[int, bytes] = {}

    def feed(self, handle: int, pb: int, chunk: bytes) -> bytes | None:
        buf = self.buffers.get(handle, b"")
        buf += chunk
        if len(buf) < 4:
            self.buffers[handle] = buf
            return None
        cid = struct.unpack("<H", buf[2:4])[0]
        l2len = struct.unpack("<H", buf[0:2])[0]
        if len(buf) < 4 + l2len:
            self.buffers[handle] = buf
            return None
        pdu = buf[4:4 + l2len]
        rest = buf[4 + l2len:]
        self.buffers[handle] = rest
        if cid != 4:
            return None
        return pdu


def parse_att(pdu: bytes):
    """Возвращает (название, handle или -1 если его нет в PDU, значение)."""
    if not pdu:
        return None
    entry = ATT_OPCODES.get(pdu[0])
    if entry is None:
        return None
    name, has_handle = entry
    if has_handle:
        if len(pdu) < 3:
            return None
        return name, struct.unpack("<H", pdu[1:3])[0], pdu[3:]
    return name, -1, pdu[1:]


def hx(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


def ascii_view(data: bytes) -> str:
    return "".join(chr(b) if 32 <= b < 127 else "." for b in data)


def scan(path: str, mac: str | None, only_writes: bool, show_all_handles: bool) -> None:
    packets, datalink = read_btsnoop(path)
    hci = packets_to_hci(packets, datalink)
    reassembler = L2capReassembler()

    handle_of_interest: set[int] = set()
    mac_lower = mac.lower() if mac else None

    # Первым проходом решаем, какие ACL handle относятся к нашему байку:
    # ищем его MAC в LE Connection Complete событиях (HCI event 0x3E, subevent 0x01).
    for ts, pkt_type, payload in hci:
        if pkt_type != 4 or len(payload) < 3:
            continue
        if payload[0] != 0x3E or payload[1] != 0x01 or len(payload) < 13:
            continue
        # payload: [0x3E][subevent][status][handle lo][handle hi][role][addr type][addr 6]
        addr = ":".join(f"{b:02X}" for b in reversed(payload[7:13]))
        if mac_lower is None or addr.lower() == mac_lower:
            handle = struct.unpack("<H", payload[3:5])[0] & 0x0FFF
            handle_of_interest.add(handle)
            print(f"LE Connect: handle={handle} peer={addr} "
                  f"status={payload[2]:#04x}", file=sys.stderr)

    if not handle_of_interest:
        print("# не нашёл LE Connection Complete в логе — разбираю все ACL handle",
              file=sys.stderr)
        handle_of_interest = None  # type: ignore[assignment]

    sequence = []
    for ts, pkt_type, payload in hci:
        if pkt_type != 2:  # только ACL
            continue
        for handle, pb, chunk in iter_l2cap_from_acl(payload):
            if handle_of_interest is not None and handle not in handle_of_interest:
                continue
            pdu = reassembler.feed(handle, pb, chunk)
            if pdu is None:
                continue
            parsed = parse_att(pdu)
            if parsed is None:
                continue
            name, char_handle, value = parsed
            if not show_all_handles and 0 <= char_handle < 0x0010:
                # 0x0001..0x000F — служебные (GAP), почти всегда шум
                continue
            if only_writes and name not in ("Write Command", "Write Request", "Notification", "Indication"):
                continue
            sequence.append((ts, name, char_handle, value))

    if not sequence:
        print("# ATT-операций не найдено. Проверьте, что в логе есть LE-трафик и что "
              "MAC указан верно.", file=sys.stderr)
        return

    print(f"# ATT-операций: {len(sequence)}")
    print(f"{'t,ms':>10}  {'op':<14} {'handle':>6}  hex / ascii")
    t0 = sequence[0][0]
    for ts, name, char_handle, value in sequence:
        dt = (ts - t0) / 1000.0
        print(f"{dt:10.1f}  {name:<14} {char_handle:#06x}  {hx(value)}  |{ascii_view(value)}|")

    # Подсказка: что именно писать в приложении
    writes = [(h, v) for _, n, h, v in sequence
              if n in ("Write Command", "Write Request") and len(v) > 0 and h > 0]
    if writes:
        print("\n# Кандидаты для повтора в BluetoothYaBike (первые 20 уникальных):", file=sys.stderr)
        seen = set()
        for h, v in writes:
            key = (h, bytes(v))
            if key in seen:
                continue
            seen.add(key)
            print(f"#   handle {h:#06x}: {hx(v)}", file=sys.stderr)
            if len(seen) >= 20:
                break


def selftest() -> None:
    # Простой ATT Write Command: opcode 0x12, handle 0x0011 (LE), value 01 02 03
    pdu = bytes([0x12, 0x11, 0x00, 0x01, 0x02, 0x03])
    assert parse_att(pdu) == ("Write Command", 0x11, bytes([1, 2, 3]))
    # Read Response: без handle, только значение
    assert parse_att(bytes([0x0B, 0xDE, 0xAD])) == ("Read Response", -1, bytes([0xDE, 0xAD]))
    # Notification: opcode 0x1B
    assert parse_att(bytes([0x1B, 0x22, 0x00, 0xAA])) == ("Notification", 0x22, bytes([0xAA]))
    # Склейка L2CAP: два фрагмента ACL
    l2cap = struct.pack("<HH", len(pdu), 4) + pdu
    r = L2capReassembler()
    first = r.feed(0x0040, 0x2, l2cap[:3])
    assert first is None
    second = r.feed(0x0040, 0x2, l2cap[3:])
    assert second == pdu, (second, pdu)
    # ACL-парсер HCI
    acl = struct.pack("<HH", 0x0040 | (0x2 << 12), len(l2cap)) + l2cap
    assert list(iter_l2cap_from_acl(acl))[0][0] == 0x0040
    print("selftest ok")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log", nargs="?", help="путь к btsnoop_hci.log")
    ap.add_argument("--mac", help="MAC байка, например AA:BB:CC:DD:EE:FF (фильтр по LE-соединению)")
    ap.add_argument("--writes", action="store_true",
                    help="только записи и уведомления (без чтений)")
    ap.add_argument("--all-handles", action="store_true",
                    help="не отбрасывать служебные handle 0x0001..0x000F")
    ap.add_argument("--selftest", action="store_true", help="проверка парсера на встроенных данных")
    args = ap.parse_args()

    if args.selftest:
        selftest()
        return
    if not args.log:
        ap.print_help()
        sys.exit(2)
    scan(args.log, args.mac, args.writes, args.all_handles)


if __name__ == "__main__":
    main()
