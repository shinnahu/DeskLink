// Runs a program in a window station of its own: its own clipboard, and no windows on the screen. For checks that
// need a test host's clipboard without touching the one of whoever is using this PC.
// Build (the C# compiler that comes with Windows):
//   %WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /out:build\StationRun.exe tools\StationRun.cs
//
//   StationRun wait - <program> [arguments]    start it there, wait for it, exit with its exit code
// "-" is the station Windows names after this logon session (giving one a name of its own takes an administrator).
// StationRun stays until the program ends: the program's desktop is gone if nothing holds it before the program has
// opened it. The rest work on the clipboard of the station they are started in, so start them with "wait -" too:
//   StationRun seq                             the station, and its clipboard's sequence number (up with every change)
//   StationRun getclip <file>                  into a UTF-8 file: the station, the number of formats, the text
//                                              (exit code 1: no text)
//   StationRun setclip <file>                  a UTF-8 file's text onto the clipboard, as a copy made on the PC
//   StationRun clearclip                       nothing on the clipboard
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

static class StationRun {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct STARTUPINFO {
        public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }

    [DllImport("user32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr CreateWindowStationW(string name, uint flags, uint access, IntPtr sa);
    [DllImport("user32", SetLastError = true)] static extern IntPtr GetProcessWindowStation();
    [DllImport("user32", SetLastError = true)] static extern bool SetProcessWindowStation(IntPtr h);
    [DllImport("user32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr CreateDesktopW(string name, IntPtr device, IntPtr devmode, uint flags, uint access, IntPtr sa);
    [DllImport("user32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool GetUserObjectInformationW(IntPtr h, int index, StringBuilder info, int len, out int needed);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CreateProcessW(string app, StringBuilder cmd, IntPtr pa, IntPtr ta, bool inherit, uint flags,
        IntPtr env, string cwd, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
    [DllImport("kernel32")] static extern uint WaitForSingleObject(IntPtr h, uint ms);
    [DllImport("kernel32")] static extern bool GetExitCodeProcess(IntPtr h, out uint code);

    [DllImport("user32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr CreateWindowExW(uint ex, string cls, string name, uint style, int x, int y, int w, int h,
        IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);
    [DllImport("user32", SetLastError = true)] static extern bool OpenClipboard(IntPtr hwnd);
    [DllImport("user32")] static extern bool CloseClipboard();
    [DllImport("user32")] static extern bool EmptyClipboard();
    [DllImport("user32")] static extern IntPtr GetClipboardData(uint fmt);
    [DllImport("user32", SetLastError = true)] static extern IntPtr SetClipboardData(uint fmt, IntPtr h);
    [DllImport("user32")] static extern int CountClipboardFormats();
    [DllImport("user32")] static extern uint GetClipboardSequenceNumber();
    [DllImport("kernel32")] static extern IntPtr GlobalLock(IntPtr h);
    [DllImport("kernel32")] static extern bool GlobalUnlock(IntPtr h);
    [DllImport("kernel32")] static extern IntPtr GlobalAlloc(uint flags, UIntPtr bytes);

    const uint CF_UNICODETEXT = 13, GENERIC_ALL = 0x10000000, CREATE_NO_WINDOW = 0x08000000;

    static string StationName() {
        var sb = new StringBuilder(256);
        int n;
        return GetUserObjectInformationW(GetProcessWindowStation(), 2 /* UOI_NAME */, sb, 512, out n) ? sb.ToString() : "?";
    }

    static bool OpenClip(IntPtr owner) {
        for (int i = 0; i < 40; i++) {
            if (OpenClipboard(owner)) return true;
            System.Threading.Thread.Sleep(25);
        }
        return false;
    }

    static int Main(string[] a) {
        if (a.Length == 1 && a[0] == "seq") { Console.WriteLine(StationName() + " " + GetClipboardSequenceNumber()); return 0; }
        if (a.Length == 2 && a[0] == "getclip") {
            if (!OpenClip(IntPtr.Zero)) return 3;
            string text = null;
            int formats = CountClipboardFormats();
            IntPtr h = GetClipboardData(CF_UNICODETEXT);
            if (h != IntPtr.Zero) {
                IntPtr p = GlobalLock(h);
                if (p != IntPtr.Zero) { text = Marshal.PtrToStringUni(p); GlobalUnlock(h); }
            }
            CloseClipboard();
            File.WriteAllText(a[1], StationName() + "\n" + formats + "\n" + (text ?? ""), new UTF8Encoding(false));
            return text == null ? 1 : 0;
        }
        if (a.Length == 1 && a[0] == "clearclip") { // a change the PC doesn't pass on: there's no text to send
            if (!OpenClip(IntPtr.Zero)) return 3;
            bool emptied = EmptyClipboard();
            CloseClipboard();
            return emptied ? 0 : 4;
        }
        if (a.Length == 2 && a[0] == "setclip") {
            string text = File.ReadAllText(a[1], Encoding.UTF8);
            // a window to own the clipboard: SetClipboardData isn't meant to work after EmptyClipboard without one
            IntPtr owner = CreateWindowExW(0, "STATIC", "", 0, 0, 0, 0, 0, new IntPtr(-3) /* message-only */, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            if (!OpenClip(owner)) return 3;
            EmptyClipboard();
            byte[] b = Encoding.Unicode.GetBytes(text + "\0");
            IntPtr hg = GlobalAlloc(2 /* GMEM_MOVEABLE */, (UIntPtr)(uint)b.Length);
            Marshal.Copy(b, 0, GlobalLock(hg), b.Length);
            GlobalUnlock(hg);
            bool ok = SetClipboardData(CF_UNICODETEXT, hg) != IntPtr.Zero;
            CloseClipboard();
            return ok ? 0 : 4;
        }
        if (a.Length >= 3 && a[0] == "wait") {
            string station = a[1] == "-" ? null : a[1];
            IntPtr mine = GetProcessWindowStation();
            IntPtr ws = CreateWindowStationW(station, 0, GENERIC_ALL, IntPtr.Zero); // opens it when it's there already
            if (ws == IntPtr.Zero) { Console.Error.WriteLine("CreateWindowStation: " + Marshal.GetLastWin32Error()); return 2; }
            if (!SetProcessWindowStation(ws)) { Console.Error.WriteLine("SetProcessWindowStation: " + Marshal.GetLastWin32Error()); return 2; }
            IntPtr desk = CreateDesktopW("Default", IntPtr.Zero, IntPtr.Zero, 0, GENERIC_ALL, IntPtr.Zero);
            int deskErr = Marshal.GetLastWin32Error();
            station = StationName();
            SetProcessWindowStation(mine);
            if (desk == IntPtr.Zero) { Console.Error.WriteLine("CreateDesktop: " + deskErr); return 2; }
            var si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(typeof(STARTUPINFO));
            si.lpDesktop = station + "\\Default";
            PROCESS_INFORMATION pi;
            var cmd = new StringBuilder(32768);
            for (int i = 2; i < a.Length; i++) { // the program and each of its arguments, quoted as Windows reads them
                if (i > 2) cmd.Append(' ');
                if (a[i].Length > 0 && a[i].IndexOfAny(new[] { ' ', '\t', '"' }) < 0) { cmd.Append(a[i]); continue; }
                cmd.Append('"');
                int slashes = 0;
                foreach (char ch in a[i]) {
                    if (ch == '\\') { slashes++; continue; }
                    cmd.Append('\\', ch == '"' ? slashes * 2 + 1 : slashes).Append(ch);
                    slashes = 0;
                }
                cmd.Append('\\', slashes * 2).Append('"');
            }
            if (!CreateProcessW(null, cmd, IntPtr.Zero, IntPtr.Zero, false, CREATE_NO_WINDOW, IntPtr.Zero, null, ref si, out pi)) {
                Console.Error.WriteLine("CreateProcess: " + Marshal.GetLastWin32Error());
                return 2;
            }
            WaitForSingleObject(pi.hProcess, 0xFFFFFFFF);
            uint code;
            GetExitCodeProcess(pi.hProcess, out code);
            return (int)code;
        }
        Console.Error.WriteLine("usage: StationRun wait - <program> [arguments] | seq | getclip <file> | setclip <file> | clearclip");
        return 64;
    }
}
