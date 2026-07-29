package pl.zabka.wyczysctms;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Arrays;
import java.util.List;

public class PermissionClickerAccessibilityService extends AccessibilityService {
    private long lastClickMs = 0;
    private final List<String> allowTexts = Arrays.asList(
            "Zezwól", "ZEZWÓL", "Zezwalaj", "ZEZWALAJ",
            "Zezwól tylko podczas używania aplikacji",
            "Podczas używania aplikacji",
            "Tylko podczas używania aplikacji",
            "Podczas korzystania z aplikacji",
            "Przy używaniu aplikacji",
            "Zezwól na dostęp",
            "Allow", "ALLOW", "Allow only while using the app", "While using the app", "Allow access",
            "Zainstaluj", "INSTALL", "Install", "Gotowe", "Done", "OK"
    );

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = SystemClock.uptimeMillis();
        if (now - lastClickMs < 500) return;
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;
        try {
            if (clickAllowedButtons(rootNode)) lastClickMs = now;
        } finally { rootNode.recycle(); }
    }

    private boolean clickAllowedButtons(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (matches(node.getText()) || matches(node.getContentDescription())) {
            if (clickNodeOrParent(node)) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean clicked = clickAllowedButtons(child);
                child.recycle();
                if (clicked) return true;
            }
        }
        return false;
    }

    private boolean matches(CharSequence value) {
        if (value == null) return false;
        String v = value.toString().trim();
        for (String allowText : allowTexts) if (v.equalsIgnoreCase(allowText)) return true;
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable() && current.isEnabled()) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            current = current.getParent();
        }
        return false;
    }
    @Override public void onInterrupt() { }
}
