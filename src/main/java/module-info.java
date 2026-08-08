/// Provides pure-Java AV1 decoding, AVIF container reading, and optional JavaFX integration.
module org.glavo.avif {
    requires static org.jetbrains.annotations;

    // Optional dependencies; if present, functionality in the org.glavo.avif.javafx package can be used.
    requires static javafx.graphics;

    // Optional dependencies; only used for the Demo application, not required when used as a library.
    requires static javafx.controls;

    exports org.glavo.avif;
    exports org.glavo.avif.av1;
    exports org.glavo.avif.javafx;
}
