module org.glavo.avif {
    requires static org.jetbrains.annotations;

    // Optional dependencies; if present, functionality in the org.glavo.javif.javafx package can be used.
    requires static javafx.graphics;

    // Optional dependencies; only used for the Demo application, not required when used as a library.
    requires static javafx.controls;

    exports org.glavo.avif;
    exports org.glavo.avif.decode;
    exports org.glavo.avif.javafx;
}
