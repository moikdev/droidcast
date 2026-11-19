"""Summarize cell bounds and selection from a directory of Android hierarchy captures."""

import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def emoji_cells(hierarchy):
    cells = []
    for node in ET.parse(hierarchy).iter("node"):
        resource_id = node.get("resource-id", "")
        if not resource_id.startswith("emoji-cell-"):
            continue
        bounds = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.attrib["bounds"])
        if not bounds:
            raise ValueError(f"Invalid bounds for {resource_id} in {hierarchy}")
        cells.append({
            "id": resource_id.removeprefix("emoji-cell-"),
            "bounds_px": list(map(int, bounds.groups())),
            "selected": node.get("checked") == "true" or node.get("selected") == "true",
        })
    return sorted(cells, key=lambda cell: (cell["bounds_px"][1], cell["bounds_px"][0]))


def grid_geometry(directory):
    result = {}
    for path in sorted(directory.glob("*.xml")):
        cells = emoji_cells(path)
        if cells:
            top = cells[0]["bounds_px"][1]
            row = [cell["bounds_px"] for cell in cells if cell["bounds_px"][1] == top]
            result[path.stem] = {
                "columns_in_first_visible_row": len(row),
                "first_row_bounds_px": row,
                "selected_visible_ids": ["emoji-cell-" + cell["id"] for cell in cells if cell["selected"]],
            }
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--cells", action="store_true", help="list every visible cell in reading order")
    args = parser.parse_args()
    if args.cells:
        result = {path.stem: emoji_cells(path) for path in sorted(args.directory.glob("*.xml"))}
    else:
        result = grid_geometry(args.directory)
    print(json.dumps(result, sort_keys=True, indent=2))
