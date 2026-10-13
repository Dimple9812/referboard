package com.referboard.profile.service;

import java.util.UUID;

/** In-process signal (Spring event) that a profile changed; handled after commit to embed and publish. */
public record ProfileChanged(UUID userId) {
}
