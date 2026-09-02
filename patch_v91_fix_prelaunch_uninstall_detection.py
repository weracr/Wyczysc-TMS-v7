#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/MainActivity.java'
SERV = ROOT / 'app/src/main/java/pl/zabka/wyczysctms/PermissionClickerAccessibilityService.java'
if not MAIN.exists() or not SERV.exists():
    raise SystemExit('Uruchom skrypt w głównym katalogu repo, obok folderu app.')

m = MAIN.read_text(encoding='utf-8')
s = SERV.read_text(encoding='utf-8')

# 1. Nie wywołuj DPM na PM95, bo aplikacja nie jest Device Owner/Profile Owner.
#    Po instalacji od razu otwórz ustawienia TMS.
pat = r'private void grantTmsPermissionsAfterInstall\(\) \{.*?\n    \}'
new = '''private void grantTmsPermissionsAfterInstall() {
        Toast.makeText(
                this,
                "Najpierw ustawiam lokalizację TMS, potem uruchomię aplikację.",
                Toast.LENGTH_LONG
        ).show();
        openTmsSettingsBeforeFirstLaunch();
    }'''
if not re.search(pat, m, flags=re.S):
    raise SystemExit('Nie znaleziono grantTmsPermissionsAfterInstall() w MainActivity.java')
m = re.sub(pat, new, m, count=1, flags=re.S)

if 'private void openTmsSettingsBeforeFirstLaunch()' not in m:
    method = '''
    private void openTmsSettingsBeforeFirstLaunch() {
        setFlowMode(MODE_GRANT_TMS_PERMISSIONS);
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + detectedTmsPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            clearFlowMode();
            Toast.makeText(this, "Nie można otworzyć ustawień TMS: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

'''
    marker = '    private void launchTmsForRuntimePermissions() {'
    if marker not in m:
        raise SystemExit('Nie znaleziono miejsca na openTmsSettingsBeforeFirstLaunch()')
    m = m.replace(marker, method + marker, 1)

# 2. Odinstalowanie: dialog ze screena ma przycisk OK i bywa raportowany przez różne pakiety.
pat = r'private boolean isUninstallDialog\(String pkg, String text\) \{.*?\n    \}'
if not re.search(pat, s, flags=re.S):
    pat = r'private boolean isUninstallDialog\(String packageName, String text\) \{.*?\n    \}'
new = '''private boolean isUninstallDialog(String pkg, String text) {
        return (text.contains("odinstalowac te aplikacje")
                || text.contains("odinstalować tę aplikację")
                || text.contains("odinstaluj")
                || text.contains("uninstall"))
                && (text.contains("tms") || text.contains("falcon") || text.contains("zabka"));
    }'''
if not re.search(pat, s, flags=re.S):
    raise SystemExit('Nie znaleziono isUninstallDialog() w serwisie')
s = re.sub(pat, new, s, count=1, flags=re.S)

# 3. Klik odinstalowania: najpierw tekst OK, potem fizyczny punkt ze screena.
old = 'return Action.text("uninstall", 1500, Arrays.asList("Odinstaluj", "Uninstall", "OK", "Ok"));'
new_action = 'return Action.textWithFallback("uninstall", 1500, Arrays.asList("OK", "Ok", "Odinstaluj", "Uninstall"), 861, 1169);'
if old in s:
    s = s.replace(old, new_action, 1)
else:
    old2 = 'return ScreenAction.text("uninstall", 1500, Arrays.asList("Odinstaluj", "Uninstall", "OK", "Ok"));'
    if old2 in s:
        s = s.replace(old2, old2, 1)  # v82 nieobsługiwany przez tę fabrykę

# 4. Dodaj fallback do Action, jeśli to wersja v90.
if 'private static final class Action' in s:
    # wykonanie fallbacku
    old_exec = 'clicked = clickVisibleText(current, action.labels);'
    new_exec = '''clicked = clickVisibleText(current, action.labels);
                if (!clicked && action.referenceX > 0 && action.referenceY > 0) {
                    clicked = tapPhysicalPoint(action.referenceX, action.referenceY);
                }'''
    if old_exec in s and 'tapPhysicalPoint(action.referenceX' not in s:
        s = s.replace(old_exec, new_exec, 1)

    # pola/konstruktor Action
    s = s.replace('''        final List<String> labels;

        private Action(String key, long delayMs, List<String> labels) {
            this.key = key;
            this.delayMs = delayMs;
            this.labels = labels;
        }''', '''        final List<String> labels;
        final int referenceX;
        final int referenceY;

        private Action(String key, long delayMs, List<String> labels, int referenceX, int referenceY) {
            this.key = key;
            this.delayMs = delayMs;
            this.labels = labels;
            this.referenceX = referenceX;
            this.referenceY = referenceY;
        }''')
    s = s.replace('return new Action(key, delayMs, labels);', 'return new Action(key, delayMs, labels, 0, 0);')
    if 'static Action textWithFallback(' not in s:
        marker = '''        static Action text(String key, long delayMs, List<String> labels) {
            return new Action(key, delayMs, labels, 0, 0);
        }
'''
        factory = marker + '''
        static Action textWithFallback(String key, long delayMs, List<String> labels,
                                       int referenceX, int referenceY) {
            return new Action(key, delayMs, labels, referenceX, referenceY);
        }
'''
        if marker not in s:
            raise SystemExit('Nie znaleziono fabryki Action.text()')
        s = s.replace(marker, factory, 1)

    # fizyczny gest bez skalowania na PM95
    helper = '''    private boolean tapPhysicalPoint(int x, int y) {
        if (!canClickNow()) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 80, 180);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

'''
    marker = '    private boolean clickVisibleText('
    if 'private boolean tapPhysicalPoint(' not in s:
        if marker not in s:
            raise SystemExit('Nie znaleziono miejsca na tapPhysicalPoint()')
        s = s.replace(marker, helper + marker, 1)

# 5. Pierwsza lokalizacja: luźniejsze wykrycie. Screen pokazuje, że serwis jej nie klasyfikuje.
if 'private boolean isInitialLocationDialog(' in s:
    pat_loc = r'private boolean isInitialLocationDialog\(String text\) \{.*?\n    \}'
    loc = '''private boolean isInitialLocationDialog(String text) {
        return text.contains("tylko tym razem")
                && text.contains("nie zezwalaj")
                && (text.contains("lokaliz")
                || text.contains("dokladna")
                || text.contains("przyblizona"));
    }'''
    s = re.sub(pat_loc, loc, s, count=1, flags=re.S)

# 6. Sprawdź konfigurację usługi i włącz gesty, jeśli plik XML istnieje.
xml_candidates = list((ROOT / 'app/src/main/res').rglob('*.xml'))
for xp in xml_candidates:
    xs = xp.read_text(encoding='utf-8', errors='ignore')
    if '<accessibility-service' in xs:
        if 'android:canPerformGestures=' in xs:
            xs = re.sub(r'android:canPerformGestures="[^"]*"', 'android:canPerformGestures="true"', xs)
        else:
            xs = xs.replace('<accessibility-service', '<accessibility-service\n    android:canPerformGestures="true"', 1)
        if 'android:canRetrieveWindowContent=' in xs:
            xs = re.sub(r'android:canRetrieveWindowContent="[^"]*"', 'android:canRetrieveWindowContent="true"', xs)
        else:
            xs = xs.replace('<accessibility-service', '<accessibility-service\n    android:canRetrieveWindowContent="true"', 1)
        xp.write_text(xs, encoding='utf-8')

for name, text in [('MainActivity.java', m), ('PermissionClickerAccessibilityService.java', s)]:
    for token in ['<br>', '&lt;', '&gt;', '-&gt;']:
        if token in text:
            raise SystemExit(f'{name}: znaleziono HTML {token}')
    if text.count('{') != text.count('}'):
        raise SystemExit(f'{name}: niezgodne klamry {text.count("{")} / {text.count("}")}')

MAIN.write_text(m, encoding='utf-8')
SERV.write_text(s, encoding='utf-8')
print('OK: usunięto komunikat Device Owner, poprawiono wykrycie/klik Odinstaluj i włączono gesty Accessibility')
