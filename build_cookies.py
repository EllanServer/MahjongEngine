#!/usr/bin/env python3
from datetime import datetime, timezone

cookies_raw = """__Host-user_session_same_site	AVVPlZBmA8TT3Tl53W_2ZJ0J1tVjCo2m23lRarPD8MozvhSw	github.com	/	2026-07-31T08:26:59.616Z	77	✓	✓	Strict	Medium
_device_id	a7b1286eb4f1495fc78ca0b3e75ad886	github.com	/	2027-07-17T08:26:59.616Z	42	✓	✓	Lax	Medium
_gh_sess	V1UbTPCz9Kbv1s2WFIJ9wU47EsSEGF%2BvzvOLus6aP8iQokZKp9k3M6sxFqR70LpHaX%2BfNAP9VeWl38r4ApVFkF%2FcmPF1yq7XC2DnQ9ZuahynLS6fgPfNpY73UBPC4zq6ER5KLuZcZ5IVVsC%2Fv2MUs8hrMutx2lKNGPib3yUtqBX2pvm9V5lDT22c5xhbvP02woLRq3NtGPOWsYTucb06yLxiaZP%2Bndup8kfA%2FdAayaenCWgcGc0axpz8ALaTt%2Fl8w%2FvJybe%2FQGp%2BezAuc9FtRXt7pM4Vnz9ekYOv%2BOWwd4VcE0WljTXFJv0co4zG5PuB27hX4whBnFcHL6qRCt0dzCuIfKNNbmdPXZv%2BpKHIlEPfisNfa1jLJu%2FQyWJNh5QYyfIM8iRZnxO%2Bcp1ZPfj9gRk43c%2BhLyhYSqCriybYOn%2F2oyk4FitUw8RByivA%2Bz1bHru23Uyc8OgUG2Kr--mlBeAEpqjYU0TS4v--pjXF%2FCC3oNi1Rxj0UY0WQQ%3D%3D	github.com	/	会话	556	✓	✓	Lax	Medium
_octo	GH1.1.500426361.1772520236	.github.com	/	2027-03-03T06:43:55.932Z	31		✓	Lax	Medium
_qimei_fingerprint	0cd861c0d10123ff2667d4670d6290cf	.github.com	/	2027-04-13T13:50:13.000Z	50				Medium
_qimei_h38	19c5838e331e20a0b0a07f930200000661a218	.github.com	/	2027-04-02T15:34:19.000Z	48				Medium
_qimei_i_1	6db375839c0955d3c29fab33588526e2f7bfa4a3120a0385e2862b582493206c6163319339d8e0dddeb1c5cc	.github.com	/	2027-04-02T15:34:19.000Z	98				Medium
_qimei_i_3	57df6ed1c00801d8c0c4f73759d07ae6ffeda0f2150f52d4b3de2c5a2695256f603137943c89e2aed4b6	.github.com	/	2027-02-24T14:17:38.000Z	94				Medium
_qimei_uuid42	1a2181611261007a331e20a0b0a07f93633bffbc27	.github.com	/	2027-02-24T14:17:38.000Z	55				Medium
color_mode	%7B%22color_mode%22%3A%22auto%22%2C%22light_theme%22%3A%7B%22name%22%3A%22light%22%2C%22color_mode%22%3A%22light%22%7D%2C%22dark_theme%22%3A%7B%22name%22%3A%22dark%22%2C%22color_mode%22%3A%22dark%22%7D%7D	.github.com	/	会话	214		✓	Lax	Medium
cpu_bucket	xlg	.github.com	/	会话	13		✓	Lax	Medium
datadome	9_2T8D_WtAtQVZJaplNrUQJXOL1Z9CH0w1yOYhQdZ9_AhGwD~~4ziv_jnzBa3TuEBUBFgtwubecof6zq~YAlRx6kIhRP~gosnBpjbcXksk056wRQpMrzuMaXCcCwBBtP	.github.com	/	2027-03-03T06:43:57.698Z	136		✓	Lax	Medium
dotcom_user	Arbousier1	.github.com	/	2027-03-06T05:12:43.825Z	21	✓	✓	Lax	Medium
GHCC	Required:1-Analytics:1-SocialMedia:1-Advertising:1	.github.com	/	2026-09-02T05:05:04.000Z	54		✓	Lax	Medium
last_write_ms	1784240034342	github.com	/	会话	26	✓	✓	Lax	Medium
logged_in	yes	.github.com	/	2027-03-06T05:12:43.825Z	12	✓	✓	Lax	Medium
MicrosoftApplicationsTelemetryDeviceId	b14d2673-787c-4053-ad86-0d0759b42945	github.com	/	2027-05-29T12:05:31.822Z	74		✓	None	Medium
MSFPC	GUID=fc0abe363d8c485fa4875342f25691ef&HASH=fc0a&LV=202602&V=4&LU=1771922710571	github.com	/	2027-03-06T05:05:08.701Z	83		✓	None	Medium
preferred_color_mode	dark	.github.com	/	会话	24		✓	Lax	Medium
tz	Asia%2FTaipei	.github.com	/	会话	15		✓	Lax	Medium
user_session	AVVPlZBmA8TT3Tl53W_2ZJ0J1tVjCo2m23lRarPD8MozvhSw	github.com	/	2026-07-31T08:26:59.616Z	60	✓	✓	Lax	Medium"""

def parse_expires(s):
    if s == "会话":
        return 0
    dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    return int(dt.timestamp())

lines = ["# Netscape HTTP Cookie File", "#", ""]
for line in cookies_raw.strip().split("\n"):
    parts = line.split("\t")
    name, value, domain, path, expires_str, size, httponly, secure, samesite, priority = parts[:10]
    include_subdomains = domain.startswith(".")
    secure_flag = "TRUE" if secure == "✓" else "FALSE"
    expires_ts = parse_expires(expires_str)
    domain_out = f"#HttpOnly_{domain}" if httponly == "✓" else domain
    lines.append(f"{domain_out}\t{'TRUE' if include_subdomains else 'FALSE'}\t{path}\t{secure_flag}\t{expires_ts}\t{name}\t{value}")

output = "\n".join(lines) + "\n"
with open("/workspace/.github_cookies.txt", "w") as f:
    f.write(output)
print(f"Wrote {len(lines)-3} cookies to /workspace/.github_cookies.txt")
