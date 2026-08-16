package me.pipi.easyshare.exceptions

import io.ktor.utils.io.CancellationException

class CancelledByUserException(val isRemote: Boolean) :
    CancellationException("Cancelled by user")