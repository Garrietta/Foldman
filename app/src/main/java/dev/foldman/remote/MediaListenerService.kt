package dev.foldman.remote

import android.service.notification.NotificationListenerService

/**
 * Does nothing itself: Android only lets apps with an enabled notification listener read other
 * apps' media sessions, which is how Foldman follows YT Music and Apple Music.
 */
class MediaListenerService : NotificationListenerService()
