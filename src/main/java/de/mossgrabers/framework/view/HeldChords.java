// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.framework.view;

import java.util.ArrayList;
import java.util.List;

import de.mossgrabers.framework.daw.midi.IMidiInput;
import de.mossgrabers.framework.daw.midi.MidiConstants;


/**
 * The chords of the held pads, so that a note which two held chords share sounds until both are
 * released. The root of a chord is played by the note input of the pad, its note off cannot be
 * held back: if another held chord still contains it, it is sent on again.
 *
 * @author Jürgen Moßgraber
 */
public class HeldChords
{
    private record HeldChord (int key, int root, int [] notes, int velocity)
    {
        boolean contains (final int note)
        {
            if (this.root == note)
                return true;
            for (final int n: this.notes)
            {
                if (n == note)
                    return true;
            }
            return false;
        }
    }


    private final List<HeldChord> held = new ArrayList<> ();


    /**
     * Forget all held chords.
     */
    public synchronized void clear ()
    {
        this.held.clear ();
    }


    /**
     * Test if the pad with the given key holds a chord.
     *
     * @param key The key of the pad
     * @return True if held
     */
    public synchronized boolean isHeld (final int key)
    {
        return this.find (key) >= 0;
    }


    /**
     * A pad of a chord was pressed (velocity > 0) or released: send the note on or off of the
     * added notes of the chord.
     *
     * @param input Where to send the notes
     * @param channel The MIDI channel
     * @param key The key of the pad
     * @param root The root note, played by the note input of the pad
     * @param chord The added notes
     * @param velocity The velocity, 0 for the release
     */
    public synchronized void play (final IMidiInput input, final int channel, final int key, final int root, final int [] chord, final int velocity)
    {
        final int status = MidiConstants.CMD_NOTE_ON + channel;
        final int index = this.find (key);

        if (velocity > 0)
        {
            for (final int note: chord)
            {
                if (this.getSoundingVelocity (note) < 0)
                    input.sendRawMidiEvent (status, note, velocity);
            }
            if (index >= 0)
                this.held.remove (index);
            this.held.add (new HeldChord (key, root, chord.clone (), velocity));
            return;
        }

        if (index < 0)
        {
            // Not pressed in this view
            for (final int note: chord)
                input.sendRawMidiEvent (status, note, 0);
            return;
        }

        final HeldChord released = this.held.remove (index);
        for (final int note: released.notes ())
        {
            if (this.getSoundingVelocity (note) < 0)
                input.sendRawMidiEvent (status, note, 0);
        }
        // The note input has ended the root, play it again for the other chord
        final int rootVelocity = this.getSoundingVelocity (released.root ());
        if (rootVelocity > 0)
            input.sendRawMidiEvent (status, released.root (), rootVelocity);
    }


    private int find (final int key)
    {
        for (int i = 0; i < this.held.size (); i++)
        {
            if (this.held.get (i).key () == key)
                return i;
        }
        return -1;
    }


    private int getSoundingVelocity (final int note)
    {
        for (final HeldChord chord: this.held)
        {
            if (chord.contains (note))
                return chord.velocity ();
        }
        return -1;
    }
}
