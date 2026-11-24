"""Read-only Windows process-tree CPU and working-set sampling for acceptance."""
import ctypes as c
from ctypes import wintypes as w
import os


class Entry(c.Structure):
    _fields_ = [('size', w.DWORD), ('usage', w.DWORD), ('pid', w.DWORD), ('heap', c.c_size_t),
                ('module', w.DWORD), ('threads', w.DWORD), ('parent', w.DWORD),
                ('priority', w.LONG), ('flags', w.DWORD), ('exe', w.WCHAR * 260)]


class Memory(c.Structure):
    _fields_ = [('size', w.DWORD), ('faults', w.DWORD)] + [(name, c.c_size_t) for name in
        ('peakWorking', 'working', 'peakPaged', 'paged', 'peakNonpaged', 'nonpaged', 'pagefile', 'peakPagefile')]


def sample_tree(root=None):
    kernel, psapi = c.WinDLL('kernel32', use_last_error=True), c.WinDLL('psapi')
    kernel.CreateToolhelp32Snapshot.restype = w.HANDLE
    kernel.OpenProcess.restype = w.HANDLE
    kernel.CloseHandle.argtypes = [w.HANDLE]
    kernel.Process32FirstW.argtypes = kernel.Process32NextW.argtypes = [w.HANDLE, c.POINTER(Entry)]
    kernel.GetProcessTimes.argtypes = [w.HANDLE] + [c.POINTER(w.FILETIME)] * 4
    psapi.GetProcessMemoryInfo.argtypes = [w.HANDLE, c.POINTER(Memory), w.DWORD]
    handle = kernel.CreateToolhelp32Snapshot(2, 0)
    parents = {}
    try:
        entry = Entry(); entry.size = c.sizeof(entry)
        more = kernel.Process32FirstW(handle, c.byref(entry))
        while more:
            parents[entry.pid] = entry.parent
            more = kernel.Process32NextW(handle, c.byref(entry))
    finally:
        kernel.CloseHandle(handle)
    members = {root or os.getpid()}
    while True:
        expanded = members | {pid for pid, parent in parents.items() if parent in members}
        if expanded == members: break
        members = expanded
    values = {}
    for pid in members:
        process = kernel.OpenProcess(0x410, False, pid)
        if not process: continue
        try:
            times = [w.FILETIME() for _ in range(4)]
            memory = Memory(); memory.size = c.sizeof(memory)
            if kernel.GetProcessTimes(process, *[c.byref(t) for t in times]) and psapi.GetProcessMemoryInfo(process, c.byref(memory), memory.size):
                values[pid] = {'cpu': sum((t.dwHighDateTime << 32) + t.dwLowDateTime for t in times[2:]) / 1e7,
                               'rss': memory.working}
        finally:
            kernel.CloseHandle(process)
    return values
