package io.github.klaw.cli.ui

private val SPINNER_FRAMES = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

/**
 * Simple synchronous spinner that prints animated frames to stdout.
 * Call [tick] in a polling loop and [done] when the operation completes.
 */
internal class Spinner(
    private val message: String,
) {
    private var frame = 0

    /** Print next spinner frame in-place (uses carriage return). */
    fun tick() {
        val f = SPINNER_FRAMES[frame % SPINNER_FRAMES.size]
        print("\r${AnsiColors.CYAN}$f${AnsiColors.RESET} $message")
        frame++
    }

    /** Clear the spinner line and print a success message. */
    fun done(successMessage: String) {
        print("\r")
        println("${AnsiColors.GREEN}✓${AnsiColors.RESET} $successMessage")
    }

    /** Clear the spinner line and print a failure message. */
    fun fail(failMessage: String) {
        print("\r")
        println("${AnsiColors.RED}✗${AnsiColors.RESET} $failMessage")
    }
}
