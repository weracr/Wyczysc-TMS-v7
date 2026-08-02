#!/usr/bin/env python3
from pathlib import Path

p = Path.cwd() / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"
if not p.exists():
    raise SystemExit(f"Nie znaleziono pliku: {p}")

s = p.read_text(encoding="utf-8")
required_import = "import android.widget.LinearLayout;"

if required_import not in s:
    anchor = "import android.widget.FrameLayout;"
    if anchor in s:
        s = s.replace(anchor, anchor + "\n" + required_import, 1)
    else:
        anchor = "import android.widget.TextView;"
        if anchor not in s:
            raise SystemExit("Nie znaleziono sekcji importów android.widget.")
        s = s.replace(anchor, required_import + "\n" + anchor, 1)

if s.count(required_import) != 1:
    raise SystemExit("Import LinearLayout występuje nieprawidłową liczbę razy.")

p.write_text(s, encoding="utf-8")
print("OK: dodano import android.widget.LinearLayout; błąd kompilacji v77 naprawiony")
