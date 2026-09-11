from __future__ import annotations

import html
import json
from datetime import datetime
from typing import Any

_STYLE = """
:root{color-scheme:dark;--bg:#111217;--panel:#181b22;--line:#262b35;--ink:#d8dee9;
--muted:#8b93a3;--good:#56b98b;--warn:#d9a441;--bad:#c96a5f;--bar:#3d7fb5}
*{box-sizing:border-box}
body{margin:0;padding:24px;background:var(--bg);color:var(--ink);
font:14px/1.5 ui-sans-serif,-apple-system,"Helvetica Neue",sans-serif}
h1{font-size:18px;margin:0 0 2px;font-weight:600}
h2{font-size:13px;margin:0 0 12px;color:var(--muted);font-weight:600;
letter-spacing:.08em;text-transform:uppercase}
.meta{color:var(--muted);font-size:12px;margin-bottom:20px}
.meta code{color:var(--ink);background:var(--panel);padding:1px 6px;border-radius:3px}
.grid{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));
margin-bottom:20px}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:16px}
.name{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.08em;
margin-bottom:10px}
.big{font-size:34px;font-weight:600;font-variant-numeric:tabular-nums;line-height:1}
.sub{color:var(--muted);font-size:12px;margin-top:6px;font-variant-numeric:tabular-nums}
table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}
th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);font-size:13px}
th{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.06em;
font-weight:600}
td.n,th.n{text-align:right}
.track{height:6px;background:#22262f;border-radius:3px;overflow:hidden;min-width:90px}
.fill{height:100%;background:var(--bar);border-radius:3px}
.note{background:var(--panel);border:1px solid var(--line);border-left:3px solid var(--warn);
border-radius:6px;padding:14px 16px;margin-bottom:20px}
.note b{color:var(--warn)}
.miss{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;
color:var(--muted)}
.miss span{color:var(--bad)}
.wrap{overflow-x:auto}
"""


def _bar(value: float) -> str:
    pct = max(0.0, min(1.0, value)) * 100
    return f'<div class="track"><div class="fill" style="width:{pct:.1f}%"></div></div>'


def render_html(report: dict[str, Any]) -> str:
    esc = html.escape
    results: dict[str, Any] = report["results"]
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")

    tiles = []
    for name, data in results.items():
        overall = data["overall"]
        tiles.append(
            f'<div class="panel"><div class="name">{esc(name)}</div>'
            f'<div class="big">{overall["recall_at_k"]:.4f}</div>'
            f'<div class="sub">Recall@{report["k"]} &middot; MRR {overall["mrr"]:.4f} '
            f'&middot; {overall["found_cases"]}/{overall["cases"]} cases</div></div>'
        )

    rows = []
    for name, data in results.items():
        for dim in ("by_difficulty", "by_split"):
            for group, stats in data[dim].items():
                rows.append(
                    f"<tr><td>{esc(name)}</td><td>{esc(group)}</td>"
                    f'<td class="n">{stats["recall_at_k"]:.4f}</td>'
                    f'<td style="width:120px">{_bar(stats["recall_at_k"])}</td>'
                    f'<td class="n">{stats["mrr"]:.4f}</td>'
                    f'<td class="n">{stats["found_cases"]}/{stats["cases"]}</td></tr>'
                )

    misses = []
    for name, data in results.items():
        for miss in data["misses"]:
            misses.append(
                f'<div class="miss"><span>{esc(name)}</span> &middot; '
                f'{esc(miss["id"])} &middot; {esc(miss["question"])}</div>'
            )

    case_count = next(iter(results.values()))["overall"]["cases"]
    per_case = 1 / case_count if case_count else 0
    reviewed = "reviewed" if report.get("reviewed") else "DRAFT (reviewed=false)"

    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<title>Retrieval Evaluation</title><style>{_STYLE}</style></head><body>
<h1>Retrieval Evaluation</h1>
<div class="meta">
  index <code>{esc(str(report.get("physical_index", "-")))}</code>
  &middot; k={report["k"]} &middot; cases {case_count} &middot; {esc(reviewed)}
  &middot; cases file <code>{esc(str(report.get("cases_path", "-")))}</code>
  &middot; generated {generated}
</div>
<div class="grid">{"".join(tiles)}</div>
<div class="note"><b>측정 한계</b> &mdash; 케이스 {case_count}건이므로 1건 =
recall {per_case:.4f}. 이보다 작거나 같은 차이는 우열의 근거가 아니다.
한 번 측정이며 재현 반복은 하지 않았다.</div>
<h2>Breakdown</h2>
<div class="wrap"><table>
<tr><th>retriever</th><th>group</th><th class="n">Recall@{report["k"]}</th><th></th>
<th class="n">MRR</th><th class="n">found</th></tr>
{"".join(rows)}
</table></div>
<h2 style="margin-top:22px">Misses</h2>
{"".join(misses) or '<div class="miss">none</div>'}
</body></html>"""


def write_html(report: dict[str, Any], path: str) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(render_html(report))


def write_json(report: dict[str, Any], path: str) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2, sort_keys=True)
