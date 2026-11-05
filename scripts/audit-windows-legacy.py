"""Reject known Win8+/Win10+ static imports; this does not replace testing on old Windows."""
import sys

BLOCKED_DLLS={'bcryptprimitives.dll','api-ms-win-core-synch-l1-2-0.dll'}
BLOCKED_APIS={'ProcessPrng','WaitOnAddress','WakeByAddressAll','WakeByAddressSingle','CreateFile2',
              'GetTempPath2W','GetTempPath2A','GetDpiForWindow','GetDpiForSystem','SetThreadDescription',
              'SetProcessDpiAwarenessContext','GetSystemTimePreciseAsFileTime','CreateDXGIFactory2'}

def validate(subsystem, imports):
    if subsystem > (6,1): raise ValueError('Subsystem version exceeds Windows 7')
    for dll, symbols in imports:
        if dll.lower() in BLOCKED_DLLS or BLOCKED_APIS.intersection(symbols):
            raise ValueError(f'Unsupported legacy Windows import in {dll}')

if __name__=='__main__':
    import pefile
    image=pefile.PE(sys.argv[1])
    entries=list(getattr(image,'DIRECTORY_ENTRY_IMPORT',[]))+list(getattr(image,'DIRECTORY_ENTRY_DELAY_IMPORT',[]))
    validate((image.OPTIONAL_HEADER.MajorSubsystemVersion,image.OPTIONAL_HEADER.MinorSubsystemVersion),
        [(entry.dll.decode(),{symbol.name.decode() for symbol in entry.imports if symbol.name}) for entry in entries])
    print('Known incompatible imports absent; real Windows 7/8/8.1 tests still required')
