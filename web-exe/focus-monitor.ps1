$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class RFMon {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
}
"@
$TAB = [string][char]9
function Clean([string]$s) { if ($null -eq $s) { return '' }; return ($s -replace '[\t\r\n]+', ' ') }
$browsers = @('chrome.exe','msedge.exe','firefox.exe','brave.exe','opera.exe','opera_gx.exe','vivaldi.exe','chromium.exe','yandex.exe','arc.exe','waterfox.exe','librewolf.exe')
$names = @{}
$omni = @{}
$omniFail = @{}
$tick = 0
$lastKey = ''
$lastEmit = 0
function Get-BrowserUrl([long]$hwnd) {
  # address bar text; '' when it cannot be read or when the user is typing in it
  try {
    $el = $omni[$hwnd]
    if ($el) {
      try { $null = $el.Current.ProcessId } catch { $el = $null; $omni.Remove($hwnd) }
    }
    if (-not $el) {
      # could not be found a moment ago: try again only every 3 s (a failed search is expensive)
      if ($omniFail.ContainsKey($hwnd) -and (([Environment]::TickCount - $omniFail[$hwnd]) -lt 3000)) { return '' }
      $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$hwnd)
      if (-not $root) { $omniFail[$hwnd] = [Environment]::TickCount; return '' }
      $edit = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)
      $nm = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, 'Address and search bar')
      $both = New-Object System.Windows.Automation.AndCondition($edit, $nm)
      $el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $both)
      if (-not $el) { $el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $edit) }
      if (-not $el) { $omniFail[$hwnd] = [Environment]::TickCount; return '' }
      if ($omni.Count -gt 50) { $omni.Clear() }
      if ($omniFail.Count -gt 50) { $omniFail.Clear() }
      $omni[$hwnd] = $el
    }
    if ($el.Current.HasKeyboardFocus) { return '' }
    $vp = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
    if (-not $vp) { return '' }
    return (Clean $vp.Current.Value)
  } catch { return '' }
}
while ($true) {
  $h = [RFMon]::GetForegroundWindow()
  if ($h -ne [IntPtr]::Zero) {
    $sb = New-Object System.Text.StringBuilder 512
    [void][RFMon]::GetWindowText($h, $sb, 512)
    [uint32]$procId = 0
    [void][RFMon]::GetWindowThreadProcessId($h, [ref]$procId)
    if (-not $names.ContainsKey($procId)) {
      $pr = Get-Process -Id $procId
      if ($pr) { $names[$procId] = $pr.ProcessName + '.exe' }
    }
    $pn = [string]$names[$procId]
    $title = Clean $sb.ToString()
    $url = ''
    if ($browsers -contains $pn.ToLower()) { $url = Get-BrowserUrl ($h.ToInt64()) }
    $key = [string]$h.ToInt64() + '|' + $title + '|' + $url
    $nowMs = [Environment]::TickCount
    # report on every change, and once a second as a heartbeat (so a skipped decision is retried)
    if ($key -ne $lastKey -or ($nowMs - $lastEmit) -gt 800) {
      $lastKey = $key
      $lastEmit = $nowMs
      [Console]::Out.WriteLine('FG' + $TAB + $h.ToInt64() + $TAB + $procId + $TAB + $pn + $TAB + $title + $TAB + $url)
    }
  }
  if ($tick % 10 -eq 0) {
    if ($names.Count -gt 500) { $names.Clear() }
    foreach ($p in Get-Process) {
      if ($p.MainWindowHandle -ne 0) {
        [Console]::Out.WriteLine('P' + $TAB + $p.Id + $TAB + $p.ProcessName + '.exe' + $TAB + (Clean $p.MainWindowTitle))
      }
    }
    [Console]::Out.WriteLine('ENDP')
  }
  $tick++
  Start-Sleep -Milliseconds 100
}
