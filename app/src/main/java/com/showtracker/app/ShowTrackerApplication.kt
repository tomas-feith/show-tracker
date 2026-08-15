package com.showtracker.app

import android.app.Application

/**
 * Process-wide setup.
 *
 * Empty for now. It exists from the start because the pieces that will need it - the
 * database, the TMDB client and the periodic refresh worker - all have to be reachable
 * from a background worker as well as from an activity, and a worker can start the
 * process without any activity ever existing.
 */
class ShowTrackerApplication : Application()
