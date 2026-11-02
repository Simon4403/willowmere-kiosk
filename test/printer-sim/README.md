# Virtual printer tests

Runs the kiosk's real printing code (`Ipp`, `PrinterService`, `Prefs`, `KioskBridge`, `PwgRaster`) on a normal JVM against a farm of simulated network printers. No tablet needed.

```
bash test/printer-sim/run.sh          # everything, about 4 minutes
bash test/printer-sim/run.sh probe    # one group
```

Groups: `parse`, `local`, `describe`, `probe`, `discover`, `print`, `moved`, `bridge`, `pwg`.

## What's simulated

**The printers.** `farm.py` runs them on top of [h2g2bob/ipp-server](https://github.com/h2g2bob/ipp-server), an independent IPP implementation, pinned in `run.sh`. Every job a printer receives is saved, so the tests can check what actually arrived.

| Printer | Behaviour |
|---|---|
| Office PDF | Takes PDF and PWG raster, A4, does its own copies, supports fit-to-page |
| Ward PWG | Takes PWG raster only |
| Empty tray | Out of paper, not accepting jobs |
| Fussy | Aborts the job: document-format-error |
| No copies | No copies support, no scaling support |
| Secure jam | Encrypted (ipps), self-signed certificate; paper jam, then prints |
| Old PostScript | PostScript only, so the tablet can't use it |
| Forgetful | Finishes the job and forgets it (IPP 0x0406) |
| Busy | Answers 0x0507 (busy) |
| Cancelled | Job cancelled at the printer |

"Add by IP" targets, all on port 631:

| Address | Behaviour |
|---|---|
| 127.0.0.2 | Plain IPP |
| 127.0.0.3 | IPPS only |
| 127.0.0.4 | Uses the `/ipp` path |
| 127.0.0.5 | Nothing listening |
| 127.0.0.6 | Accepts the connection, then stays silent |
| 127.0.0.7 | An ordinary web page, not a printer |

**The tablet side.**

- `shims/` holds small JVM stand-ins for the Android classes the code touches:
  - Handler/Looper, as a single "main" thread
  - an in-memory SharedPreferences
  - a fake mDNS network, with Android's one-resolve-at-a-time rule
- `fake/KioskActivity` stands in for the kiosk screen, to test that `window.HeraKiosk` only works for Hera's own page.
- `org.json` is Android's own source, from AOSP.

## Not covered

These need a real tablet:

- Rendering PDFs to raster (`PdfRenderer`). The raster *encoder* is covered by `PwgGen` and `pwgdecode.py`.
- Android's print screen fallback.
- Lock-task behaviour.
