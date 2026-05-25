import xml.etree.ElementTree as ET

import uiautomator2 as u2


TARGETS = ("已完成", "去完成")


def contains_target(value):
    value = value or ""
    return any(target in value for target in TARGETS)


def main():
    d = u2.connect()
    xml = d.dump_hierarchy(compressed=False, pretty=True)
    with open("dump_full.xml", "w", encoding="utf-8") as f:
        f.write(xml)

    print("dump saved: dump_full.xml")
    for target in TARGETS:
        print(f"{target} count:", xml.count(target))

    print("matched nodes:")
    root = ET.fromstring(xml)
    fields = ("text", "content-desc", "resource-id", "class")
    for node in root.iter("node"):
        if not any(contains_target(node.attrib.get(field)) for field in fields):
            continue
        print(dict(node.attrib))


if __name__ == "__main__":
    main()
