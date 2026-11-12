# Who presses Stop / closes the Bearguard window?
#
# Prod has been stopped through its own UI with no script behind it: the Stop button, or the
# window closed, with no settings backup first (so not stop-bearguard.ps1), no remote-desktop
# guest, no Claude session doing computer-use and no process kill. That leaves input reaching
# the Bearguard window on this desktop. This watcher records only
# input that lands while a Bearguard window is in the foreground, plus focus changes to/from it,
# and whether Windows flags the input as INJECTED (software: SendInput/automation) or physical.
# It never records what was typed: keys are logged only as Space/Enter/Esc/Alt+F4/Tab or "key".
#
#   powershell -WindowStyle Hidden -File C:\Bearguard\tools\watch-bearguard-input.ps1
#
# Log: C:\Bearguard\logs\bearguard-input-watch.log
param([string]$Log = 'C:\Bearguard\logs\bearguard-input-watch.log')

Add-Type -ReferencedAssemblies System.Windows.Forms -TypeDefinition @'
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

public static class BgWatch {
    delegate IntPtr HookProc(int code, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] static extern IntPtr SetWindowsHookEx(int id, HookProc fn, IntPtr mod, uint tid);
    [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr h, int code, IntPtr w, IntPtr l);
    [DllImport("kernel32.dll")] static extern IntPtr GetModuleHandle(string name);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int vk);
    [StructLayout(LayoutKind.Sequential)] struct PT { public int x, y; }
    [DllImport("user32.dll")] static extern IntPtr WindowFromPoint(PT p);
    [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr h, uint flags);

    static string Under(int x, int y) {
        IntPtr h = GetAncestor(WindowFromPoint(new PT { x = x, y = y }), 2);   // GA_ROOT
        var sb = new StringBuilder(256); GetWindowText(h, sb, 256);
        uint pid; GetWindowThreadProcessId(h, out pid);
        string name = "?";
        try { name = Process.GetProcessById((int)pid).ProcessName; } catch { }
        return name + " '" + sb.ToString() + "'";
    }

    [StructLayout(LayoutKind.Sequential)] struct KB { public uint vk, scan, flags, time; public IntPtr extra; }
    [StructLayout(LayoutKind.Sequential)] struct MS { public int x, y; public uint data, flags, time; public IntPtr extra; }

    static string logPath;
    static HookProc kbProc, msProc;
    static string lastFg = "";

    static string Fg(out bool isBg) {
        IntPtr h = GetForegroundWindow();
        var sb = new StringBuilder(256); GetWindowText(h, sb, 256);
        uint pid; GetWindowThreadProcessId(h, out pid);
        string name = "?";
        try { name = Process.GetProcessById((int)pid).ProcessName; } catch { }
        string title = sb.ToString();
        isBg = name.Equals("javaw", StringComparison.OrdinalIgnoreCase) && title.Contains("Bearguard");
        return name + " pid " + pid + " '" + title + "'";
    }

    static void Write(string line) {
        try { File.AppendAllText(logPath, DateTime.Now.ToString("yyyy-MM-ddTHH:mm:ss.fff") + " " + line + Environment.NewLine); } catch { }
    }

    static IntPtr OnKey(int code, IntPtr w, IntPtr l) {
        if (code >= 0) {
            int msg = w.ToInt32();
            if (msg == 0x100 || msg == 0x104) {   // WM_KEYDOWN / WM_SYSKEYDOWN
                bool isBg; string fg = Fg(out isBg);
                if (isBg) {
                    var k = (KB)Marshal.PtrToStructure(l, typeof(KB));
                    bool alt = (GetAsyncKeyState(0x12) & 0x8000) != 0;
                    string key = k.vk == 0x20 ? "Space" : k.vk == 0x0D ? "Enter" : k.vk == 0x1B ? "Esc" : k.vk == 0x09 ? "Tab"
                               : (k.vk == 0x73 && alt) ? "Alt+F4" : k.vk == 0x12 ? "Alt" : "key";
                    bool injected = (k.flags & 0x10) != 0;
                    Write("KEY   " + key + (injected ? " INJECTED" : " physical") + " -> " + fg);
                }
            }
        }
        return CallNextHookEx(IntPtr.Zero, code, w, l);
    }

    static IntPtr OnMouse(int code, IntPtr w, IntPtr l) {
        if (code >= 0) {
            int msg = w.ToInt32();
            if (msg == 0x201 || msg == 0x204) {   // left / right button down
                bool isBg; string fg = Fg(out isBg);
                if (isBg) {
                    var m = (MS)Marshal.PtrToStructure(l, typeof(MS));
                    bool injected = (m.flags & 0x1) != 0;
                    Write("CLICK " + (msg == 0x201 ? "left" : "right") + " at " + m.x + "," + m.y + (injected ? " INJECTED" : " physical")
                          + " on " + Under(m.x, m.y) + " (foreground: " + fg + ")");
                }
            }
        }
        return CallNextHookEx(IntPtr.Zero, code, w, l);
    }

    public static void Run(string path) {
        logPath = path;
        Write("watcher started (pid " + Process.GetCurrentProcess().Id + ")");
        kbProc = OnKey; msProc = OnMouse;
        IntPtr mod = GetModuleHandle(null);
        SetWindowsHookEx(13, kbProc, mod, 0);   // WH_KEYBOARD_LL
        SetWindowsHookEx(14, msProc, mod, 0);   // WH_MOUSE_LL
        var t = new Timer { Interval = 500 };
        t.Tick += (s, e) => {
            bool isBg; string fg = Fg(out isBg);
            string tag = isBg ? "BEARGUARD" : "other";
            if (tag != lastFg) { Write("FOCUS " + (isBg ? "Bearguard window is now in front: " : "focus left Bearguard -> ") + fg); lastFg = tag; }
        };
        t.Start();
        Application.Run();
    }
}
'@

[BgWatch]::Run($Log)
