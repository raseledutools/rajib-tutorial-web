$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class RFAct {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
}
"@
function Clean([string]$s) { if ($null -eq $s) { return '' }; return ($s -replace '[\t\r\n]+', ' ') }
function Get-Title([IntPtr]$h) {
  $sb = New-Object System.Text.StringBuilder 512
  [void][RFAct]::GetWindowText($h, $sb, 512)
  return (Clean $sb.ToString())
}
# the tab the user is looking at must still be the one we decided about (the title is the active tab's title)
function Same([IntPtr]$h, [string]$title) {
  if ([RFAct]::GetForegroundWindow() -ne $h) { return $false }
  return ((Get-Title $h) -eq $title)
}
function Key([byte]$vk, [bool]$ctrl) {
  if ($ctrl) { [RFAct]::keybd_event(0x11, 0, 0, [UIntPtr]::Zero) }
  [RFAct]::keybd_event($vk, 0, 0, [UIntPtr]::Zero)
  [RFAct]::keybd_event($vk, 0, 2, [UIntPtr]::Zero)
  if ($ctrl) { [RFAct]::keybd_event(0x11, 0, 2, [UIntPtr]::Zero) }
}
$TAB = [char]9
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  $p = $line.Split($TAB)
  if ($p.Count -lt 2) { continue }
  $action = $p[0]
  $h = [IntPtr][long]$p[1]
  $url = ''
  if ($p.Count -gt 2) { $url = $p[2] }
  $title = ''
  if ($p.Count -gt 3 -and $p[3]) { try { $title = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[3])) } catch { $title = '' } }
  if ($action -eq 'closewin') {
    [void][RFAct]::PostMessage($h, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
  } elseif ($action -eq 'closetab') {
    if (Same $h $title) {
      Key 0x57 $true
      Start-Sleep -Milliseconds 60
      [void][RFAct]::ShowWindow($h, 6)
    }
  } elseif ($action -eq 'tofocus') {
    if ($url -and (Same $h $title)) {
      $old = $null
      try { $old = [System.Windows.Forms.Clipboard]::GetText() } catch {}
      try { [System.Windows.Forms.Clipboard]::SetText($url) } catch {}
      Key 0x4C $true
      Start-Sleep -Milliseconds 70
      if (Same $h $title) {
        Key 0x56 $true
        Start-Sleep -Milliseconds 60
        if (Same $h $title) { Key 0x0D $false } else { Key 0x1B $false }
      } else { Key 0x1B $false }
      Start-Sleep -Milliseconds 250
      try { if ($old) { [System.Windows.Forms.Clipboard]::SetText($old) } else { [System.Windows.Forms.Clipboard]::Clear() } } catch {}
    }
  }
}
