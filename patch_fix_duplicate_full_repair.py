#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / "app/src/main/java/pl/zabka/wyczysctms/MainActivity.java"
SERV = ROOT / "app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java"

if not MAIN.exists():
    raise SystemExit(f"Nie znaleziono pliku: {MAIN}")
if not SERV.exists():
    raise SystemExit(f"Nie znaleziono pliku: {SERV}")

main = MAIN.read_text(encoding="utf-8")
serv = SERV.read_text(encoding="utf-8")

# 1. Usuń duplikaty stałej MODE_FULL_REPAIR w obu plikach, zostawiając pierwsze wystąpienie.
def keep_first_full_repair_constant(text: str) -> str:
    line = '    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    seen = False
    out = []
    for current in text.splitlines():
        if current.strip() == line.strip():
            if seen:
                continue
            seen = True
        out.append(current)
    return "\n".join(out) + "\n"

main = keep_first_full_repair_constant(main)
serv = keep_first_full_repair_constant(serv)

# 2. Jeśli po usuwaniu nie ma stałej w MainActivity, dodaj ją za MODE_DETAILS_ONLY.
if 'MODE_FULL_REPAIR' not in main:
    main = main.replace(
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";',
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )

# 3. Jeśli po usuwaniu nie ma stałej w serwisie, dodaj ją za MODE_DETAILS_ONLY.
if 'MODE_FULL_REPAIR' not in serv:
    serv = serv.replace(
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";',
        'private static final String MODE_DETAILS_ONLY = "DETAILS_ONLY_MODE";\n    private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";'
    )

# 4. Dodaj brakującą metodę showRepairInProgressScreen() do MainActivity.
if 'private void showRepairInProgressScreen()' not in main:
    method = '''private void showRepairInProgressScreen() {
        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(40), dp(24), dp(40));
        root.setBackgroundColor(Color.rgb(16, 24, 40));

        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Naprawa TMS w toku");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView message = new TextView(this);
        message.setText("Nie dotykaj ekranu. Aplikacja automatycznie odinstaluje, zainstaluje i nada uprawnienia TMS. Po zakończeniu TMS uruchomi się automatycznie.");
        message.setTextSize(17);
        message.setTextColor(Color.rgb(234, 236, 240));
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(18), 0, dp(18));
        root.addView(message, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("Proces naprawy trwa. Nie używaj przycisków systemowych podczas działania automatyzacji.");
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(152, 162, 179));
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
    }

    '''
    if 'private void showRepairDialog()' in main:
        main = main.replace('private void showRepairDialog()', method + 'private void showRepairDialog()')
    elif 'private void askAdminPin()' in main:
        main = main.replace('private void askAdminPin()', method + 'private void askAdminPin()')
    else:
        raise SystemExit('Nie znalazłam miejsca na wklejenie showRepairInProgressScreen().')

# 5. Dodatkowe czyszczenie: duplicate blank lines max 2
main = re.sub(r'\n{4,}', '\n\n\n', main)
serv = re.sub(r'\n{4,}', '\n\n\n', serv)

# 6. Sprawdź, czy duplikaty nadal istnieją.
main_count = main.count('private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";')
serv_count = serv.count('private static final String MODE_FULL_REPAIR = "FULL_REPAIR_FLOW";')
if main_count != 1:
    raise SystemExit(f'Błąd: MainActivity ma {main_count} wystąpień MODE_FULL_REPAIR')
if serv_count != 1:
    raise SystemExit(f'Błąd: PermissionClickerAccessibilityService ma {serv_count} wystąpień MODE_FULL_REPAIR')
if 'private void showRepairInProgressScreen()' not in main:
    raise SystemExit('Błąd: nadal brakuje showRepairInProgressScreen()')

MAIN.write_text(main, encoding="utf-8")
SERV.write_text(serv, encoding="utf-8")
print("OK: usunięto duplikaty MODE_FULL_REPAIR i dodano showRepairInProgressScreen()")
