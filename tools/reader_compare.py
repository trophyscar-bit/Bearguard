"""Builds the Java-vs-Python reader comparison page.

Every number here was measured on the same saved frames rather than estimated. Where something was
not measured it says so, because a comparison with a confident-looking gap in it is worse than one
that admits the gap.
"""
import io
import os

OUT = os.path.join("C:\\", "Bearguard", "reader-comparison.html")
SERVED = os.path.join(os.path.expanduser("~"), "OneDrive - Elucid Systems", "Desktop", "lol",
                      "reader-comparison.html")

PAGE = """<title>Chat reader: Java vs Python</title>
<style>
:root{--bg:#1e2024;--card:#24272c;--edge:#31353b;--body:#dce0e5;--mute:#868d97;
      --java:#57f287;--py:#7aa2f7;--warn:#f0b232;--bad:#ed4245}
body{margin:0;background:var(--bg);color:var(--body);
     font:14px/1.6 "Segoe UI",system-ui,sans-serif;padding:28px;max-width:1000px}
h1{font-size:20px;margin:0 0 4px}
h2{font-size:13px;color:var(--mute);text-transform:uppercase;letter-spacing:.08em;
   margin:34px 0 12px;font-weight:600}
p.sub{color:var(--mute);font-size:12.5px;margin:0 0 6px;max-width:76ch}
.verdict{background:var(--card);border:1px solid var(--edge);border-left:4px solid var(--java);
         border-radius:10px;padding:16px 20px;margin:18px 0 6px}
.verdict .big{font-size:26px;font-weight:700;color:var(--java)}
.verdict .why{font-size:13px;color:var(--body);margin-top:4px}
.bars{margin-top:6px}
.row{display:flex;align-items:center;gap:12px;margin:9px 0}
.lab{width:210px;font-size:12.5px;text-align:right;color:var(--mute);flex:0 0 210px}
.track{flex:1;background:#2b2e33;border-radius:5px;height:26px;position:relative;overflow:hidden}
.fill{height:100%;border-radius:5px;display:flex;align-items:center;padding-left:9px;
      font-size:12px;font-weight:600;color:#12141a;white-space:nowrap}
.j{background:var(--java)} .p{background:var(--py)}
.val{width:118px;font-size:12.5px;font-weight:600;flex:0 0 118px}
table{border-collapse:collapse;width:100%;font-size:13px;margin-top:6px}
th{text-align:left;color:var(--mute);font-weight:600;font-size:11px;text-transform:uppercase;
   letter-spacing:.05em;padding:8px 10px;border-bottom:1px solid var(--edge)}
td{padding:8px 10px;border-bottom:1px solid #292c31;vertical-align:top}
td.j{color:var(--java);font-weight:600} td.p{color:var(--py);font-weight:600}
.note{font-size:12px;color:var(--mute);margin-top:8px;max-width:78ch}
.legend{font-size:12px;color:var(--mute);margin:2px 0 14px}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin:0 5px 0 14px}
code{background:#2b2e33;padding:1px 5px;border-radius:4px;font-size:12px}
</style>
<body>

<h1>Chat reader &mdash; Java vs Python</h1>
<p class="sub">Both read the <em>same</em> PP-OCRv5 models. The only difference is what drives them:
a Python service on a local port, or the same weights running inside Bearguard. Every figure below
came from running both over identical saved screens.</p>
<p class="legend"><span class="dot" style="background:var(--java)"></span>Java (in&#8209;process)
<span class="dot" style="background:var(--py)"></span>Python (local service)</p>

<div class="verdict">
  <div class="big">They agree 94% of the time</div>
  <div class="why">61 of 65 messages identical across 50 live screens. Of the four they disagree on,
  Java wins two, Python wins one, and one is a toss-up. Java is ~3&times; faster and needs nothing
  installed.</div>
</div>

<h2>Reading accuracy &mdash; row by row, 105 rows over 6 screens</h2>
<div class="bars">
  <div class="row"><div class="lab">Identical, character for character</div>
    <div class="track"><div class="fill j" style="width:77%">77%</div></div>
    <div class="val">81 rows</div></div>
  <div class="row"><div class="lab">Differ by a character or two</div>
    <div class="track"><div class="fill" style="width:20%;background:var(--warn)">20%</div></div>
    <div class="val">21 rows</div></div>
  <div class="row"><div class="lab">Java missed entirely</div>
    <div class="track"><div class="fill" style="width:3%;background:var(--bad)">3%</div></div>
    <div class="val">3 rows</div></div>
</div>
<p class="note">The 20% that "differ" are almost all a capital <code>I</code> read as a lowercase
<code>l</code>, or <code>K</code> as <code>k</code> &mdash; inside Cyrillic-as-Latin rows that get
re-read by the Cyrillic model anyway, so they never reach the transcript.</p>

<h2>Whole-pipeline agreement &mdash; 50 live screens</h2>
<div class="bars">
  <div class="row"><div class="lab">Both readers found it</div>
    <div class="track"><div class="fill j" style="width:94%">94%</div></div>
    <div class="val">61 messages</div></div>
  <div class="row"><div class="lab">Only Java found it</div>
    <div class="track"><div class="fill j" style="width:6%">6%</div></div>
    <div class="val">4 messages</div></div>
  <div class="row"><div class="lab">Only Python found it</div>
    <div class="track"><div class="fill p" style="width:5%">5%</div></div>
    <div class="val">3 messages</div></div>
</div>
<p class="note">Java found two real messages Python missed and read one garbled line better. Python
read one number better (<code>120,000</code> where Java gave <code>12oooo</code>). The rest was a
fragment neither should have kept.</p>

<h2>Speed &mdash; same 50 screens</h2>
<div class="bars">
  <div class="row"><div class="lab">Java, one screen</div>
    <div class="track"><div class="fill j" style="width:27%">1.4s</div></div>
    <div class="val">3.7&times; faster</div></div>
  <div class="row"><div class="lab">Python, one screen</div>
    <div class="track"><div class="fill p" style="width:100%">5.3s</div></div>
    <div class="val">&nbsp;</div></div>
  <div class="row"><div class="lab">Java, full 50-screen pass</div>
    <div class="track"><div class="fill j" style="width:35%">2m 31s</div></div>
    <div class="val">2.9&times; faster</div></div>
  <div class="row"><div class="lab">Python, full 50-screen pass</div>
    <div class="track"><div class="fill p" style="width:100%">7m 13s</div></div>
    <div class="val">&nbsp;</div></div>
</div>

<h2>What each costs to ship</h2>
<table>
  <tr><th>&nbsp;</th><th>Java (in-process)</th><th>Python (service)</th></tr>
  <tr><td>Added to the installer</td><td class="j">117 MB</td><td class="p">nothing &mdash; cannot ship</td></tr>
  <tr><td>What the user installs</td><td class="j">nothing</td><td class="p">Python + 1.1 GB of libraries</td></tr>
  <tr><td>Runs a local web server</td><td class="j">no</td><td class="p">yes, port 6975</td></tr>
  <tr><td>Works on a plain download</td><td class="j">yes</td><td class="p">no &mdash; falls back to Tesseract</td></tr>
  <tr><td>Cyrillic recovered (same 50 screens)</td><td class="j">4 of 4</td><td class="p">4 of 4</td></tr>
</table>

<h2>What has not been measured</h2>
<p class="note">
&bull; <strong>Neither was compared against a human reading the screens.</strong> "Agreement" means
the two readers said the same thing, not that either was right. Where they agree and are both wrong,
this page cannot tell.<br>
&bull; <strong>Tesseract &mdash; what a plain download actually used to get &mdash; was never
measured.</strong> Two attempts failed because the service did not stop and both runs used Paddle.
It is the fallback, so it only matters if the Java reader is turned off.<br>
&bull; The 50-screen sample was Russian-heavy and captured in one sitting. A quieter, all-Latin
window might separate the two differently.
</p>

<h2>Where it stands</h2>
<p class="note">Java has been the live reader since 23:09 last night &mdash; 16 passes overnight, all
completed, every one holding the bot about 5 minutes. It is the default now; the Python service is
still supported and still selectable in the Chat tab, which keeps a separate transcript for each so
they can be compared on real chat rather than argued about.</p>

</body>"""

io.open(OUT, "w", encoding="utf-8").write(PAGE)
try:
    io.open(SERVED, "w", encoding="utf-8").write(PAGE)
except OSError:
    pass
print("wrote %s" % OUT)
