LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    AudioFlinger.cpp            \
    AudioMixer.cpp.arm          \
    AudioResampler.cpp.arm      \
    AudioResamplerSinc.cpp.arm  \
    AudioResamplerCubic.cpp.arm \
    AudioPolicyService.cpp

LOCAL_C_INCLUDES := \
    system/media/audio_effects/include

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libbinder \
    libmedia \
    libhardware \
    libhardware_legacy \
    libeffects \
    libdl \
    libpowermanager

ifeq ($(BOARD_USES_AUDIO_LEGACY),true)
LOCAL_STATIC_LIBRARIES := \
    libcpustats
else
LOCAL_STATIC_LIBRARIES := \
    libcpustats \
    libmedia_helper
endif

LOCAL_MODULE:= libaudioflinger

include $(BUILD_SHARED_LIBRARY)
