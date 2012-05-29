/*
 * Copyright (C) 2011 Texas Instruments Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


#include "OmapLayer.h"
#include "S3DSurfaceFlinger.h"

namespace android {

OmapLayer::OmapLayer(S3DSurfaceFlinger* flinger, DisplayID display, const sp<Client>& client)
         :  Layer(flinger, display, client),
            mFlingerS3D(flinger),
            mType(eMono),
            mViewOrder(eLeftViewFirst),
            mRenderMode(eRenderStereo)
{
}

void OmapLayer::setConfig(S3DLayoutType type, S3DLayoutOrder order, S3DRenderMode mode)
{
    mType = type;
    mViewOrder = order;
    mRenderMode = mode;
    //Cause redraw of visible region
    mCurrentState.sequence++;
    requestTransaction();
}

void OmapLayer::onRemoved()
{
    mFlingerS3D->removeS3DLayer_l(this);
    Layer::onRemoved();
}

void OmapLayer::setGeometry(hwc_layer_t* hwcl)
{
    Layer::setGeometry(hwcl);
    hwcl->flags &= ~S3DLayoutTypeMask;
    hwcl->flags |= mType << S3DLayoutTypeShift;
    hwcl->flags &= ~S3DLayoutOrderMask;
    hwcl->flags |= mViewOrder << S3DLayoutOrderShift;
    hwcl->flags &= ~S3DRenderModeMask;
    hwcl->flags |= mRenderMode << S3DRenderModeShift;
}

void OmapLayer::lockPageFlip(bool& recomputeVisibleRegions)
{
    Layer::lockPageFlip(recomputeVisibleRegions);
#ifdef OMAP_ENHANCEMENT
    uint8_t s3d_type = (mCurrentLayout >> 16 ) & 0xFF;
    uint8_t s3d_order = (mCurrentLayout >> 24 ) & 0xFF;
    S3DLayoutType prevType = mType;
    switch (s3d_type) {
        case eSideBySide:
        case eTopBottom:
        case eRowInterleaved:
        case eColInterleaved:
            mType = static_cast<S3DLayoutType>(s3d_type);
            break;
        default:
            //Invalid type
            return;
    }
    switch (s3d_order) {
        case eLeftViewFirst:
        case eRightViewFirst:
            mViewOrder = static_cast<S3DLayoutOrder>(s3d_order);
        default:
            //Invalid order
            mViewOrder = eLeftViewFirst;
            break;
    }
    if (mType != prevType && mType != eMono) {
        mFlingerS3D->addS3DLayer_l(this);
    }
#endif
}

void OmapLayer::drawWithOpenGL(const Region& clip) const
{
    if (mFlingerS3D->isDefaultRender() ||
        (!isS3D() && !mFlingerS3D->isFramePackingRender())) {
        //No custom drawing needed.
        //TODO: filter Monoscopic layers when doing interleaved rendering
        //if high-quality setting is selected
        LayerBase::drawWithOpenGL(clip);
        return;
    }

    //Enable filtering for custom drawing as there scaling will occur
    glTexParameterx(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

    S3DRenderMode viewToRender = mFlingerS3D->isDrawingLeft() ? eRenderLeft : eRenderRight;
    if (isS3D()&& mRenderMode != eRenderStereo && mRenderMode != viewToRender) {
        //If the layer has been configured to render a specific view (left or right)
        //then draw that view, not the one being asked for by surfaceflinger
        if (mFlingerS3D->isFramePackingRender() || mFlingerS3D->isMonoRender()) {
            viewToRender = mRenderMode;
        }
    }

    if (isS3D() && mFlingerS3D->isInterleaveRender() && mRenderMode == eRenderStereo) {
        glEnable(GL_STENCIL_TEST);
    } else if (isS3D() && mFlingerS3D->isAnaglyphRender() && mRenderMode == eRenderStereo) {
        //Left view = RED
        glColorMask(GL_TRUE, GL_FALSE, GL_FALSE, GL_TRUE);
    }

    drawWithOpenGL(clip, isDrawingFirstHalf(viewToRender));

    //This layer draws its right view here as the viewport is not changed.
    //This is done so that blending of any higher z layers with this one is correct.
    if (isS3D() && mRenderMode == eRenderStereo && 
        !mFlingerS3D->isFramePackingRender() && !mFlingerS3D->isMonoRender()) {
        mFlingerS3D->setDrawState(S3DSurfaceFlinger::eDrawingS3DRight);
        if (mFlingerS3D->isAnaglyphRender()) {
            //right view = cyan
            glColorMask(GL_FALSE, GL_TRUE, GL_TRUE, GL_TRUE);
        }
        drawWithOpenGL(clip, isDrawingFirstHalf(eRenderRight));
        mFlingerS3D->setDrawState(S3DSurfaceFlinger::eDrawingS3DLeft);
    }

    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glDisable(GL_STENCIL_TEST);
}

void OmapLayer::drawWithOpenGL(const Region& clip, bool drawFirstHalf) const
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const uint32_t fbHeight = hw.getHeight();
    const State& s(drawingState());

    GLenum src = mPremultipliedAlpha ? GL_ONE : GL_SRC_ALPHA;
    if (UNLIKELY(s.alpha < 0xFF)) {
        const GLfloat alpha = s.alpha * (1.0f/255.0f);
        if (mPremultipliedAlpha) {
            glColor4f(alpha, alpha, alpha, alpha);
        } else {
            glColor4f(1, 1, 1, alpha);
        }
        glEnable(GL_BLEND);
        glBlendFunc(src, GL_ONE_MINUS_SRC_ALPHA);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
    } else {
        glColor4f(1, 1, 1, 1);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        if (!isOpaque()) {
            glEnable(GL_BLEND);
            glBlendFunc(src, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }
    }

    struct TexCoords {
        GLfloat u;
        GLfloat v;
    };

    TexCoords texCoords[4];
    texCoords[0].u = 0;
    texCoords[0].v = 1;
    texCoords[1].u = 0;
    texCoords[1].v = 0;
    texCoords[2].u = 1;
    texCoords[2].v = 0;
    texCoords[3].u = 1;
    texCoords[3].v = 1;

    if (isS3D()) {
        if (drawFirstHalf) {
            switch(mType) {
                //top half
                case eTopBottom:
                    texCoords[1].v = 0.5f;
                    texCoords[2].v = 0.5f;
                    break;
                //left half
                case eSideBySide:
                    texCoords[2].u = 0.5f;
                    texCoords[3].u = 0.5f;
                    break;
                default:
                    //Should never happen!
                    break;
            }
        } else {
            switch(mType) {
                //bottom half
                case eTopBottom:
                    texCoords[0].v = 0.5f;
                    texCoords[3].v = 0.5f;
                    break;
                //right half
                case eSideBySide:
                    texCoords[0].u = 0.5f;
                    texCoords[1].u = 0.5f;
                    break;
                default:
                    //Should never happen!
                    break;
            }
        }
    }

    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, mVertices);
    glTexCoordPointer(2, GL_FLOAT, 0, texCoords);

    Region::const_iterator it = clip.begin();
    Region::const_iterator const end = clip.end();
    while (it != end) {
        const Rect& r = *it++;
        GLint sy = fbHeight - (r.top + r.height());
        GLint x = r.left;
        GLsizei w = r.width();
        GLsizei h = r.height();
        mFlingerS3D->modifyCoords(x, sy, w, h);
        glScissor(x, sy, w, h);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
    }
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    glDisable(GL_BLEND);
}

};
