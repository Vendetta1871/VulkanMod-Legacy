package net.vesu.render;

/** Implemented by {@code EntityRendererM}: the terrain pass calls vanilla's private hand pass early. */
public interface IHandInScene {
    void vesu$renderHandInScene();
}
