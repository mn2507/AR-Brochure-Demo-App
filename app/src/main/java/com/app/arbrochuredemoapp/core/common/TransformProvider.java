package com.app.arbrochuredemoapp.core.common;

import com.app.arbrochuredemoapp.core.math.Matrix;

/**
 * Interface for providing information about a 3D transformation. See {@link
 * com.app.arbrochuredemoapp.core.Node}.
 *
 * @hide
 */
public interface TransformProvider {
  Matrix getWorldModelMatrix();
}
