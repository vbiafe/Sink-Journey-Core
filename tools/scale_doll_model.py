#!/usr/bin/env python3
"""Scale a doll block model's geometry to a target height.

The plushie models were lifted from ExtendedAE Plus at ~0.86 blocks tall. This
tool rescales the element geometry (not the texture or the item display
transforms) about the block's horizontal centre and the floor, so a doll can be
made roughly one block tall without touching its UVs.

    python tools/scale_doll_model.py <model.json> --height 15.4
    python tools/scale_doll_model.py <model.json> --height 15.4 --write

Without --write it only prints the scale factor and the resulting bounds.
"""

import argparse
import json
import sys


def bounds(model):
    xs, ys, zs = [], [], []
    for element in model["elements"]:
        for point in (element["from"], element["to"]):
            xs.append(point[0])
            ys.append(point[1])
            zs.append(point[2])
    return (min(xs), max(xs)), (min(ys), max(ys)), (min(zs), max(zs))


def scaled(values, factor, centre):
    return [round(centre[i] + (value - centre[i]) * factor, 5) for i, value in
            enumerate(values)]


def format_number(value):
    if value == int(value):
        return str(int(value))
    return ("%.5f" % value).rstrip("0").rstrip(".")


def dump(value, indent=0):
    pad = "  " * indent
    inner = "  " * (indent + 1)
    if isinstance(value, dict):
        if not value:
            return "{}"
        rows = ['%s%s: %s' % (inner, json.dumps(key, ensure_ascii=False),
                              dump(item, indent + 1))
                for key, item in value.items()]
        return "{\n" + ",\n".join(rows) + "\n" + pad + "}"
    if isinstance(value, list):
        if not value:
            return "[]"
        rows = [inner + dump(item, indent + 1) for item in value]
        return "[\n" + ",\n".join(rows) + "\n" + pad + "]"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return format_number(value)
    return json.dumps(value, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("--height", type=float, required=True,
                        help="target height in model units (16 = one block)")
    parser.add_argument("--centre", default="8,0,8",
                        help="scale origin as x,y,z, default 8,0,8")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    centre = [float(part) for part in args.centre.split(",")]
    with open(args.model, encoding="utf-8") as fh:
        model = json.load(fh)

    _, (low_y, high_y), _ = bounds(model)
    factor = args.height / (high_y - low_y)

    for element in model["elements"]:
        element["from"] = scaled(element["from"], factor, centre)
        element["to"] = scaled(element["to"], factor, centre)
        rotation = element.get("rotation")
        if rotation:
            rotation["origin"] = scaled(rotation["origin"], factor, centre)

    x, y, z = bounds(model)
    print("scale factor %.5f" % factor)
    print("height %.3f units (%.3f blocks)" % (y[1] - y[0], (y[1] - y[0]) / 16.0))
    print("x %.3f..%.3f  y %.3f..%.3f  z %.3f..%.3f" % (x[0], x[1], y[0], y[1], z[0], z[1]))

    if args.write:
        with open(args.model, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(dump(model) + "\n")
        print("wrote", args.model)


if __name__ == "__main__":
    sys.exit(main())
