# Saved queues

Use **Add to playlist** on a video, movie, or episode to build a queue, even when nothing is playing.
Cached Jellyfin titles can be added while offline; playback still requires a reachable source or a
completed download. Folders are not queued as implicit collections.

Open **Library → Queues**, or **Now Playing → Save and manage queues**:

1. **Play on TV** starts the first queued item through the normal TV picker.
2. **Save as…** gives the current queue a name. Subsequent edits and playback progress update that
   named queue automatically. Save under another name to keep a separate copy.
3. **Stop** ends playback while retaining the unfinished item and its position. **Resume on TV**
   reconnects through the TV picker on a later visit, including after restarting the app.
4. **New queue** leaves named queues available under **Saved queues**. **Open queue** loads a saved
   queue for inspection and resumption. Stop playback before switching queues. Replacing an unnamed
   working queue prompts the user to save it first if they want to keep it.
5. Remove an upcoming item with its remove button. A stopped unfinished item can also be removed.
   Deleting a saved queue removes its name and saved contents without deleting videos or the active
   working copy.

The video already playing is included when building/saving a queue from Now Playing. Queued media
may appear more than once; each occurrence has its own identity. Successfully started items become
the unfinished current item, and natural completion removes them from the remaining queue. A failed
start keeps the entry for retry. An explicit skip replaces the current entry. When the final queued
item finishes, the queue ends instead of continuing with unqueued series episodes.

## Persistence and ownership

- The working queue is saved automatically, even before it has a name. Renderer-confirmed progress
  is checkpointed roughly every five seconds during playback, and on pause/Stop. An abrupt process
  termination can therefore resume a few seconds before the last observed position.
- Queue state uses versioned JSON in the app's private **no-backup** directory. Writes use a synced
  temporary file, replacement by rename, and recovery from the previous committed document.
- Stored values contain stable media references and display metadata, not tokens, streaming URLs,
  proxy sessions, or live renderer connections. Artwork and detailed chapter/overview payloads are
  omitted from the saved representation; media is resolved again when resuming.
- Jellyfin entries retain the server/user identity that owned them when queued. Playback and queue
  hand-offs check that identity before accessing the source. Sign in to the original account to play
  its entries; switching accounts does not reinterpret saved item IDs.
- Local videos still require their original content permission, and downloaded videos still require
  their original account and a readable completed file. Missing items remain visible for removal or
  retry. Saving a queue does not download its media.
- Queues survive sign-out and do not expire with playback history. **Delete all app data** removes
  both the working queue and all saved queues, including recovery files.

## Verification

Unit coverage includes restart/Stop retention, save/open/new/delete behavior, repeated entries,
stale checkpoint rejection, unavailable local media, account mismatch, and interrupted-write recovery.
Physical-device checks: build a mixed local/Jellyfin queue, stop mid-item, force-stop/relaunch, resume
on a TV, and verify the remaining order and final stop. Repeat with downloaded media while offline,
a revoked local permission, and an unavailable or changed Jellyfin account.
