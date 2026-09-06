# Foreground-service declaration draft

Verify the wording against the current Play Console before submission. These answers describe the
current production behavior; the local fixture only supplies deterministic media and a receiver for
the demonstration.

## Data sync

Select **Network processing → Other**.

Do not select backup/restore, media transcoding, importing/exporting, or another task. Stream Ferry
uses this service only while fulfilling an explicit **Download for offline** request from the user.

Suggested explanation if Play Console provides a text field:

> When the user taps Download for offline, Stream Ferry downloads that selected video from the user's
> Jellyfin server into app-private storage. A data-sync foreground service keeps the user-requested
> network transfer alive, exposes progress through an ongoing notification, and stops as soon as all
> downloads complete or are cancelled. Stream Ferry does not run periodic background sync.

Local evidence video: `fgs-videos/data-sync-demo.mp4`

The recording shows the real app starting the offline download, in-app byte progress, the foreground
progress notification, leaving the app, and the completed app-private offline copy.

Video link for Play Console: **TODO — upload as an unlisted reviewer-accessible video**

## Media playback

Select **Media playback** only. Do not select picture-in-picture or Other.

Suggested explanation if Play Console provides a text field:

> After the user chooses Play on TV and selects a Cast or DLNA receiver, Stream Ferry keeps a
> MediaSession and the phone-hosted playback proxy active with a media-playback foreground service.
> This lets the television continue receiving the selected media and keeps play, pause, seek, and stop
> controls available from the app, notification, lock screen, and hardware media buttons. The service
> stops when playback ends or the user taps Stop.

Local evidence video: `fgs-videos/media-playback-demo.mp4`

The recording shows the real app discovering the configured local DLNA fixture, starting playback,
displaying Now Playing, and exposing the system media notification and controls after leaving the app.

Video link for Play Console: **TODO — upload as an unlisted reviewer-accessible video**
