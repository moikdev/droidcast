#!/usr/bin/env python3
"""Generate independent navigation expectations from Tinycast's shipped Swift model.

Requires Swift 6 and an unmodified checkout at the pinned upstream commit. This
reads the reference checkout and writes only the explicitly selected TSV output.
"""
import argparse, hashlib, subprocess, tempfile
from pathlib import Path

PIN = '37e6043f3f550febb64bdb3cff3ac29a30caad5a'
MODEL = 'Tinycast/Features/Extensions/Model/ExtensionGridGeometry.swift'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--upstream', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
assert subprocess.check_output(['git', '-C', str(a.upstream), 'rev-parse', 'HEAD']).decode().strip() == PIN
source = a.upstream / MODEL
assert source.read_bytes() == subprocess.check_output(['git', '-C', str(a.upstream), 'show', PIN + ':' + MODEL])
harness = '''import Foundation
@main struct GridOracle {
    static func main() {
        let sections = [[1], [2], [3], [4], [5], [8], [11], [1,4], [4,5], [2,8,1], [8,3,6]]
        for counts in sections {
            for columns in 1...8 {
                let geometry = ExtensionGridGeometry(counts: counts, columns: columns)
                for index in 0..<geometry.cellCount {
                    print("\\(counts.map(String.init).joined(separator: ","))\\t\\(columns)\\t\\(index)\\t\\(geometry.up(from:index))\\t\\(geometry.down(from:index))")
                }
            }
        }
    }
}
'''
with tempfile.TemporaryDirectory(prefix='droidcast-grid-oracle-') as temp:
    root = Path(temp)
    main = root / 'Oracle.swift'
    main.write_text(harness)
    binary = root / 'oracle'
    subprocess.run(['swiftc', '-swift-version', '6', str(source), str(main), '-o', str(binary)], check=True)
    rows = subprocess.check_output([str(binary)]).decode()
assert len(rows.splitlines()) == 608
header = '# Tinycast ' + PIN + '\n# ' + MODEL + ' sha256=' + hashlib.sha256(source.read_bytes()).hexdigest() + '\n# counts\tcolumns\tindex\tup\tdown\n'
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(header + rows)
print('608 valid-cell scenarios / 1216 up-down expectations generated from the unchanged upstream model')
