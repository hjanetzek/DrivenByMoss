// Copyright (c) 2026 Hannes Janetzek
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.osc.module;

import de.mossgrabers.controller.osc.exception.MissingCommandException;
import de.mossgrabers.controller.osc.exception.UnknownCommandException;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.IModel;
import de.mossgrabers.framework.daw.clip.INoteClip;
import de.mossgrabers.framework.daw.clip.IStepInfo;
import de.mossgrabers.framework.daw.clip.NotePosition;
import de.mossgrabers.framework.daw.clip.StepState;
import de.mossgrabers.framework.daw.constants.DeviceID;
import de.mossgrabers.framework.daw.data.IChannel;
import de.mossgrabers.framework.daw.data.ISpecificDevice;
import de.mossgrabers.framework.daw.data.bank.IChannelBank;
import de.mossgrabers.framework.osc.IOpenSoundControlWriter;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Set;


/**
 * Additional values for the conformance tests of the Rust port of DrivenByMoss (see
 * DrivenByMoss4Reaper rust/doc/CONFORMANCE_TESTING_PLAN.md). Reports values, and has commands
 * only to set up the test fixture. Enabled with the environment variable DBM_OSC_CONFORMANCE,
 * since the clip grid must be created during init.
 * <ul>
 * <li>/clip/step/{channel}/{step}/{note}: the steps of a fixed grid of the cursor clip (64 steps
 * of 1/16 from the clip start, all notes, 0-based), all values of a step as one text in the step
 * format of the Rust core (StepInfo::to_wire), "0" if the step became empty.</li>
 * <li>/track/{1-8}/playingNotes: the notes which play on the track, "note:velocity" separated
 * by spaces.</li>
 * <li>/conformance/device/slots: the names of the slots (nested device chains, e.g. FX) of the
 * cursor device, separated by "|", empty if it has none.</li>
 * <li>Command /conformance/action {id}: invokes the Bitwig action with that identifier, e.g. to
 * build parts of the fixture which have no API, such as the layers of an Instrument Layer.</li>
 * <li>Command /conformance/actions {text}: reports /conformance/actions with all actions whose
 * identifier or name contains the text (ignoring case), one "identifier|name" per line.</li>
 * </ul>
 */
public class ConformanceModule extends AbstractModule
{
    /** The environment variable which enables the module. */
    public static final String ENABLE_VARIABLE = "DBM_OSC_CONFORMANCE";

    private static final int   NUM_STEPS       = 64;
    private static final int   NUM_ROWS        = 128;
    private static final int   NUM_CHANNELS    = 16;

    private final INoteClip    clip;
    private final int          numTracks;
    private final int [] []    playingNotes;
    private final Set<Integer> reportedSteps   = new HashSet<> ();
    private final NotePosition position        = new NotePosition ();
    private String             foundActions    = null;


    /**
     * Constructor; must be called during init.
     *
     * @param host The host
     * @param model The model
     * @param writer The writer
     * @param numTracks The number of tracks of the track bank
     */
    public ConformanceModule (final IHost host, final IModel model, final IOpenSoundControlWriter writer, final int numTracks)
    {
        super (host, model, writer);

        this.clip = model.getNoteClip (NUM_STEPS, NUM_ROWS);
        this.clip.setStepLength (0.25);
        this.clip.scrollToPage (0);

        this.numTracks = numTracks;
        this.playingNotes = new int [numTracks] [128];
        model.getTrackBank ().addNoteObserver ( (trackIndex, note, velocity) -> {
            if (trackIndex >= 0 && trackIndex < this.numTracks && note >= 0 && note < 128)
                this.playingNotes[trackIndex][note] = velocity;
        });
    }


    /**
     * Is the module enabled?
     *
     * @return True if the environment variable is set
     */
    public static boolean isEnabled ()
    {
        final String value = System.getenv (ENABLE_VARIABLE);
        return value != null && !value.isBlank ();
    }


    /** {@inheritDoc} */
    @Override
    public String [] getSupportedCommands ()
    {
        return new String []
        {
            "conformance"
        };
    }


    /** {@inheritDoc} */
    @Override
    public void execute (final String command, final LinkedList<String> path, final Object value) throws UnknownCommandException, MissingCommandException
    {
        if (!"conformance".equals (command))
            throw new UnknownCommandException (command);

        final String subCommand = getSubCommand (path);
        if (value == null || value.toString ().isBlank ())
            throw new MissingCommandException ();
        final String text = value.toString ();
        switch (subCommand)
        {
            case "action":
                this.model.getApplication ().invokeAction (text);
                break;

            case "actions":
                final String filter = text.toLowerCase (Locale.ROOT);
                final StringBuilder found = new StringBuilder ();
                for (final String description: this.model.getApplication ().getActionDescriptions ())
                {
                    if (!description.toLowerCase (Locale.ROOT).contains (filter))
                        continue;
                    if (!found.isEmpty ())
                        found.append ('\n');
                    found.append (description);
                }
                // Sent with the next flush
                this.foundActions = found.toString ();
                break;

            default:
                throw new UnknownCommandException (subCommand);
        }
    }


    /** {@inheritDoc} */
    @Override
    public void flush (final boolean dump)
    {
        this.flushSteps (dump);

        this.writer.sendOSC ("/conformance/device/slots", String.join ("|", this.model.getCursorDevice ().getSlotChains ()), dump);

        // The layers and drum pads of the primary device (OSC reports them only for the cursor
        // device)
        final ISpecificDevice primary = this.model.getSpecificDevice (DeviceID.FIRST_INSTRUMENT);
        if (primary != null)
        {
            this.flushChannels ("/conformance/primary/layer/", primary.getLayerBank (), dump);
            this.flushChannels ("/conformance/primary/drumpad/", primary.getDrumPadBank (), dump);
        }

        if (this.foundActions != null)
        {
            this.writer.sendOSC ("/conformance/actions", this.foundActions, true);
            this.foundActions = null;
        }

        for (int t = 0; t < this.numTracks; t++)
        {
            final StringBuilder notes = new StringBuilder ();
            for (int note = 0; note < 128; note++)
            {
                final int velocity = this.playingNotes[t][note];
                if (velocity > 0)
                {
                    if (!notes.isEmpty ())
                        notes.append (' ');
                    notes.append (note).append (':').append (velocity);
                }
            }
            this.writer.sendOSC ("/track/" + (t + 1) + "/playingNotes", notes.toString (), dump);
        }
    }


    /**
     * The channels of the page of a layer or drum pad bank: "{prefix}offset" (the scroll
     * position), "{prefix}{1-n}/exists|name|selected|mute|solo|volume|pan".
     */
    private void flushChannels (final String prefix, final IChannelBank<? extends IChannel> bank, final boolean dump)
    {
        this.writer.sendOSC (prefix + "offset", bank.getScrollPosition (), dump);
        for (int i = 0; i < bank.getPageSize (); i++)
        {
            final IChannel channel = bank.getItem (i);
            final String address = prefix + (i + 1) + "/";
            this.writer.sendOSC (address + "exists", channel.doesExist (), dump);
            this.writer.sendOSC (address + "name", channel.getName (), dump);
            this.writer.sendOSC (address + "selected", channel.isSelected (), dump);
            this.writer.sendOSC (address + "mute", channel.isMute (), dump);
            this.writer.sendOSC (address + "solo", channel.isSolo (), dump);
            this.writer.sendOSC (address + "volume", channel.getVolume (), dump);
            this.writer.sendOSC (address + "pan", channel.getPan (), dump);
        }
    }


    private void flushSteps (final boolean dump)
    {
        final Set<Integer> current = new HashSet<> ();
        for (int channel = 0; channel < NUM_CHANNELS; channel++)
        {
            this.position.setChannel (channel);
            for (int step = 0; step < NUM_STEPS; step++)
            {
                this.position.setStep (step);
                for (int row = 0; row < NUM_ROWS; row++)
                {
                    this.position.setNote (row);
                    final IStepInfo info = this.clip.getStep (this.position);
                    if (info.getState () == StepState.OFF)
                        continue;
                    final Integer key = Integer.valueOf ((channel * NUM_STEPS + step) * NUM_ROWS + row);
                    current.add (key);
                    this.writer.sendOSC (address (channel, step, row), format (info), dump);
                }
            }
        }

        // Steps which became empty
        for (final Integer key: this.reportedSteps)
        {
            if (current.contains (key))
                continue;
            final int value = key.intValue ();
            this.writer.sendOSC (address (value / (NUM_STEPS * NUM_ROWS), value / NUM_ROWS % NUM_STEPS, value % NUM_ROWS), "0", dump);
        }
        this.reportedSteps.clear ();
        this.reportedSteps.addAll (current);
    }


    private static String address (final int channel, final int step, final int row)
    {
        return "/clip/step/" + channel + "/" + step + "/" + row;
    }


    /** All values of a step in the step format of the Rust core (StepInfo::to_wire). */
    private static String format (final IStepInfo info)
    {
        final int state = info.getState () == StepState.START ? 1 : 2;
        return String.format (Locale.ROOT, "%d %d %d %s %s %s %s %s %s %s %s %s %d %s %d %d %d %d %d %d %d %s %s %s", Integer.valueOf (state), flag (info.isSelected ()), flag (info.isMuted ()), num (info.getDuration ()), num (info.getVelocity ()), num (info.getVelocitySpread ()), num (info.getReleaseVelocity ()), num (info.getPressure ()), num (info.getTimbre ()), num (info.getPan ()), num (info.getTranspose ()), num (info.getGain ()), flag (info.isChanceEnabled ()), num (info.getChance ()), flag (info.isOccurrenceEnabled ()), Integer.valueOf (info.getOccurrence ().ordinal ()), flag (info.isRecurrenceEnabled ()), Integer.valueOf (info.getRecurrenceLength ()), Integer.valueOf (info.getRecurrenceMask ()), flag (info.isRepeatEnabled ()), Integer.valueOf (info.getRepeatCount ()), num (info.getRepeatCurve ()), num (info.getRepeatVelocityCurve ()), num (info.getRepeatVelocityEnd ()));
    }


    private static Integer flag (final boolean value)
    {
        return Integer.valueOf (value ? 1 : 0);
    }


    private static String num (final double value)
    {
        return Double.toString (value);
    }
}
