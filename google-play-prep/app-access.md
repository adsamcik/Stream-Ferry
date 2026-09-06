# App access draft

## Reviewer path without an account

Stream Ferry can be reviewed without a login:

1. Open the app.
2. Choose **Use videos on this device** on the welcome screen.
3. Grant access only if the reviewer wants to select device media.
4. The Library, Downloads, Settings, About, Diagnostics, and Privacy controls remain accessible.

TV playback requires a compatible Cast or DLNA/UPnP device on the same network. The app should not
be marked as entirely access-restricted solely because Jellyfin is optional.

## Jellyfin review path

The Jellyfin connection flow requires a reachable server URL and credentials supplied by the
tester. If Google needs to review this flow, enter a temporary, least-privilege demo account in Play
Console's **App access** instructions. Never place credentials in this repository, store listing,
screenshots, release notes, or support documents.

Before submission, verify the demo server is reachable from outside the developer's LAN and remove
or rotate its credentials after the review window.
