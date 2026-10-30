# OSC conformance module: build and install

Branch `conformance-osc` (from `linux-maschine`). It extends the OSC controller with values for
the conformance tests of the Rust port of DrivenByMoss (DrivenByMoss4Reaper,
`rust/doc/CONFORMANCE_TESTING_PLAN.md`). The tests compare the Java and the Rust version on
Bitwig; the OSC controller is the neutral observer of Bitwig's state.

## The code

- `src/main/java/de/mossgrabers/controller/osc/module/ConformanceModule.java`: the additional
  values, and commands only for building the test fixture.
- `src/main/java/de/mossgrabers/controller/osc/OSCControllerSetup.java` (`createSurface`, after the
  `ClipModule`): adds the module if it is enabled.

The module is enabled with the environment variable `DBM_OSC_CONFORMANCE` (any non-empty value)
when Bitwig starts, since the objects it needs (e.g. the clip grid) can only be created during
init. Without it, the extension behaves exactly as without the branch.

It currently reports:

- `/clip/step/{channel}/{step}/{note}`: the steps of a fixed grid of the cursor clip (64 steps of
  1/16 from the clip start, all notes, 0-based), all values of a step as one text in the step
  format of the Rust core (`StepInfo::to_wire`), `0` when a step became empty.
- `/track/{1-8}/playingNotes`: the notes playing on the track, `note:velocity` separated by
  spaces.
- `/conformance/primary/layer/{1-8}/…` and `/conformance/primary/drumpad/{1-16}/…`: the layers
  and drum pads of the page of the primary device (`exists`, `name`, `selected`, `mute`,
  `solo`, `volume`, `pan`), plus `…/offset`, the scroll position of the bank. OSC reports layers
  and drum pads only for the cursor device.
- `/conformance/device/slots`: the slots (nested chains, e.g. `FX|Note` for Polysynth) of the
  cursor device, separated by `|`. An Instrument Layer has only `FX`, so slots do not give a
  way to create layers either.
- `/conformance/actions` (after the command of the same name): the matching actions, one
  `identifier|name` per line.

Commands of the module:

- `/conformance/action {id}`: invokes the Bitwig action with that identifier. Actions act on
  what has the focus in Bitwig's GUI, which the API cannot set (e.g. `Group` groups the
  selected track, not the selected device).
- `/conformance/actions {text}`: reports all actions whose identifier or name contains the
  text, ignoring case.

Commands in `DeviceModule` (for `/device/…` and `/primary/…`), not tied to the module:

- `/device/layer/{1-8}/browse`, `/device/drumpad/{1-16}/browse`: open the browser to add a device
  at the start of the layer's or pad's chain.
- `/device/layer/{1-8}/addDevice {name}`, `/device/drumpad/{1-16}/addDevice {name}`: insert a
  Bitwig device by name (from `resources/devices/*.txt`) at the end of the chain.

These fill layers that exist. Nothing creates a layer: inserting into an empty layer slot does
nothing, the API has no call for it, and no Bitwig action adds one. An Instrument Layer with
layers has to come from a preset or be built in Bitwig.

## Rules for extensions

- Only add values. Never change what any controller does, and never change existing OSC
  addresses: the Java controllers under test run in the same extension.
- Add new values to `ConformanceModule` (or a new module next to it, enabled the same way).
- Send values with `this.writer.sendOSC (address, value, dump)`; the writer only sends changes,
  `dump` is true on `/refresh`.
- New files with LF line endings; existing files keep their line endings (several use CRLF).
- List each new address above and in `rust/doc/CONFORMANCE_TESTING_PLAN.md` §2 of
  DrivenByMoss4Reaper (the "Branch conformance-osc" list).
- Commit on the branch `conformance-osc` only.

## Build

Java 21 language level, Maven; the dependencies are in the local Maven repository, so it builds
offline:

```
cd ~/bastel/github/DrivenByMoss
mvn -o -q package -DskipTests
```

The result is `target/DrivenByMoss-26.7.0.jar`. Do not use `mvn install`: its copy step needs
`bitwig.extension.directory`, which is not set here.

## Install

Bitwig loads `~/Bitwig Studio/Extensions/DrivenByMoss.bwextension` (the jar under another name).
Keep a copy of the installed one before the first install of a session:

```
E=~/"Bitwig Studio/Extensions"
cp -p "$E/DrivenByMoss.bwextension" target/DrivenByMoss.bwextension.before-conformance
cp target/DrivenByMoss-26.7.0.jar "$E/DrivenByMoss.bwextension"
```

Then let Bitwig load it: deactivate and activate the "Open Sound Control" controller (and the
Java controllers under test) in Bitwig's controller settings, or restart Bitwig. Bitwig must be
started with `DBM_OSC_CONFORMANCE=1` in its environment for the module to be active.

## Check

The OSC controller sends to 127.0.0.1:9000 (receives on 8000). With nothing else listening on
port 9000:

```
python3 ~/bastel/github/DrivenByMoss4Reaper/rust/backends/bitwig/tools/osc_monitor.py --port 9000 --quiet-vu --filter /clip/step --filter /track/1/playingNotes
```

`/track/N/playingNotes` appears right after the start; `/clip/step/...` when the selected clip
has notes. Send `/refresh` to get all values again:
`osc_monitor.py --send /refresh`.
