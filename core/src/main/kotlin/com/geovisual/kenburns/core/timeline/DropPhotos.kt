package com.geovisual.kenburns.core.timeline

import com.geovisual.kenburns.core.model.PhotoItem
import com.geovisual.kenburns.core.model.Project

/**
 * The project without the photos [keep] rejects (export leaves out photos whose file is missing).
 *
 * [PhotoItem.transitionOverride] belongs to the boundary after its photo, so dropping photo B in
 * A, B, C must not leave A's override covering the new A->C boundary. The transition into C was
 * B's, so the kept photo before a dropped run takes the override of the last dropped photo in
 * that run (null = project default). Dropped photos before the first kept one, or after the last,
 * have no boundary to carry.
 */
fun Project.keepingPhotos(keep: (PhotoItem) -> Boolean): Project {
    val out = ArrayList<PhotoItem>(photos.size)
    for (p in photos) {
        if (keep(p)) out += p
        else if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(transitionOverride = p.transitionOverride)
    }
    return copy(photos = out)
}
