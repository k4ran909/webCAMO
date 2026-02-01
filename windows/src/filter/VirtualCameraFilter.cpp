/**
 * WebCAMO DirectShow Virtual Camera Filter
 *
 * This DLL registers as a DirectShow video capture source filter.
 * It reads frames from shared memory and provides them to applications.
 *
 * To register: regsvr32 WebCAMOFilter.dll
 * To unregister: regsvr32 /u WebCAMOFilter.dll
 */

#define WIN32_LEAN_AND_MEAN
#include <dvdmedia.h>
#include <initguid.h>
#include <olectl.h>
#include <streams.h>
#include <windows.h>


// Constants
constexpr int VIDEO_WIDTH = 1280;
constexpr int VIDEO_HEIGHT = 720;
constexpr int VIDEO_FPS = 30;
constexpr REFERENCE_TIME FRAME_INTERVAL = 10000000LL / VIDEO_FPS;

// GUIDs
// {E8F2A3B4-5C6D-7E8F-9A0B-C1D2E3F4A5B6}
DEFINE_GUID(CLSID_WebCAMOCamera, 0xe8f2a3b4, 0x5c6d, 0x7e8f, 0x9a, 0x0b, 0xc1,
            0xd2, 0xe3, 0xf4, 0xa5, 0xb6);

// Forward declarations
class WebCAMOSource;
class WebCAMOStream;

/**
 * Virtual Camera Source Filter
 */
class WebCAMOSource : public CSource {
public:
  static CUnknown *WINAPI CreateInstance(LPUNKNOWN lpunk, HRESULT *phr);

  STDMETHODIMP QueryInterface(REFIID riid, void **ppv) {
    if (riid == IID_IAMFilterMiscFlags) {
      return E_NOINTERFACE;
    }
    return CSource::QueryInterface(riid, ppv);
  }

private:
  WebCAMOSource(LPUNKNOWN lpunk, HRESULT *phr);
};

/**
 * Virtual Camera Output Stream
 */
class WebCAMOStream : public CSourceStream {
public:
  WebCAMOStream(HRESULT *phr, WebCAMOSource *pParent, LPCWSTR pPinName);
  ~WebCAMOStream();

  // CSourceStream overrides
  HRESULT FillBuffer(IMediaSample *pSample) override;
  HRESULT DecideBufferSize(IMemAllocator *pAlloc,
                           ALLOCATOR_PROPERTIES *pProps) override;
  HRESULT SetMediaType(const CMediaType *pMediaType) override;
  HRESULT GetMediaType(int iPosition, CMediaType *pmt) override;
  HRESULT CheckMediaType(const CMediaType *pMediaType) override;

  // Quality control
  STDMETHODIMP Notify(IBaseFilter *pSender, Quality q) override {
    return E_NOTIMPL;
  }

private:
  bool OpenSharedMemory();
  void CloseSharedMemory();
  void GenerateTestPattern(BYTE *pData, long lDataLen);

  HANDLE m_sharedMemoryHandle = nullptr;
  void *m_sharedMemoryPtr = nullptr;
  HANDLE m_frameEvent = nullptr;

  REFERENCE_TIME m_rtLastTime = 0;
  int m_frameNumber = 0;
  CCritSec m_cSharedState;
};

// ============================================================================
// WebCAMOSource Implementation
// ============================================================================

WebCAMOSource::WebCAMOSource(LPUNKNOWN lpunk, HRESULT *phr)
    : CSource(L"WebCAMO Camera", lpunk, CLSID_WebCAMOCamera) {

  CAutoLock cAutoLock(&m_cStateLock);

  // Create our output pin
  WebCAMOStream *pStream = new WebCAMOStream(phr, this, L"Output");
  if (pStream == nullptr) {
    if (phr)
      *phr = E_OUTOFMEMORY;
  }
}

CUnknown *WINAPI WebCAMOSource::CreateInstance(LPUNKNOWN lpunk, HRESULT *phr) {
  WebCAMOSource *pNewFilter = new WebCAMOSource(lpunk, phr);
  if (pNewFilter == nullptr) {
    if (phr)
      *phr = E_OUTOFMEMORY;
  }
  return pNewFilter;
}

// ============================================================================
// WebCAMOStream Implementation
// ============================================================================

WebCAMOStream::WebCAMOStream(HRESULT *phr, WebCAMOSource *pParent,
                             LPCWSTR pPinName)
    : CSourceStream(L"WebCAMO", phr, pParent, pPinName) {
  OpenSharedMemory();
}

WebCAMOStream::~WebCAMOStream() { CloseSharedMemory(); }

HRESULT WebCAMOStream::GetMediaType(int iPosition, CMediaType *pmt) {
  CheckPointer(pmt, E_POINTER);

  if (iPosition < 0)
    return E_INVALIDARG;
  if (iPosition > 0)
    return VFW_S_NO_MORE_ITEMS;

  CAutoLock cAutoLock(m_pFilter->pStateLock());

  // Set up RGB32 format
  VIDEOINFOHEADER *pvi =
      (VIDEOINFOHEADER *)pmt->AllocFormatBuffer(sizeof(VIDEOINFOHEADER));
  if (pvi == nullptr)
    return E_OUTOFMEMORY;

  ZeroMemory(pvi, sizeof(VIDEOINFOHEADER));

  pvi->bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
  pvi->bmiHeader.biWidth = VIDEO_WIDTH;
  pvi->bmiHeader.biHeight = VIDEO_HEIGHT; // Positive = bottom-up
  pvi->bmiHeader.biPlanes = 1;
  pvi->bmiHeader.biBitCount = 32;
  pvi->bmiHeader.biCompression = BI_RGB;
  pvi->bmiHeader.biSizeImage = VIDEO_WIDTH * VIDEO_HEIGHT * 4;

  pvi->AvgTimePerFrame = FRAME_INTERVAL;

  pmt->SetType(&MEDIATYPE_Video);
  pmt->SetFormatType(&FORMAT_VideoInfo);
  pmt->SetSubtype(&MEDIASUBTYPE_RGB32);
  pmt->SetSampleSize(pvi->bmiHeader.biSizeImage);
  pmt->SetTemporalCompression(FALSE);

  return S_OK;
}

HRESULT WebCAMOStream::CheckMediaType(const CMediaType *pMediaType) {
  CheckPointer(pMediaType, E_POINTER);

  if (*pMediaType->Type() != MEDIATYPE_Video) {
    return E_INVALIDARG;
  }

  if (*pMediaType->Subtype() != MEDIASUBTYPE_RGB32) {
    return E_INVALIDARG;
  }

  if (*pMediaType->FormatType() != FORMAT_VideoInfo) {
    return E_INVALIDARG;
  }

  VIDEOINFOHEADER *pvi = (VIDEOINFOHEADER *)pMediaType->Format();
  if (pvi->bmiHeader.biWidth != VIDEO_WIDTH ||
      pvi->bmiHeader.biHeight != VIDEO_HEIGHT) {
    return E_INVALIDARG;
  }

  return S_OK;
}

HRESULT WebCAMOStream::SetMediaType(const CMediaType *pMediaType) {
  CAutoLock cAutoLock(m_pFilter->pStateLock());
  return CSourceStream::SetMediaType(pMediaType);
}

HRESULT WebCAMOStream::DecideBufferSize(IMemAllocator *pAlloc,
                                        ALLOCATOR_PROPERTIES *pProps) {
  CheckPointer(pAlloc, E_POINTER);
  CheckPointer(pProps, E_POINTER);

  CAutoLock cAutoLock(m_pFilter->pStateLock());

  VIDEOINFOHEADER *pvi = (VIDEOINFOHEADER *)m_mt.Format();
  pProps->cBuffers = 1;
  pProps->cbBuffer = pvi->bmiHeader.biSizeImage;

  ALLOCATOR_PROPERTIES Actual;
  HRESULT hr = pAlloc->SetProperties(pProps, &Actual);
  if (FAILED(hr))
    return hr;

  if (Actual.cbBuffer < pProps->cbBuffer) {
    return E_FAIL;
  }

  return S_OK;
}

HRESULT WebCAMOStream::FillBuffer(IMediaSample *pSample) {
  CheckPointer(pSample, E_POINTER);

  CAutoLock cAutoLock(&m_cSharedState);

  BYTE *pData;
  long lDataLen;
  pSample->GetPointer(&pData);
  lDataLen = pSample->GetSize();

  // Try to read from shared memory
  bool hasFrame = false;

  if (m_frameEvent && m_sharedMemoryPtr) {
    // Wait for new frame (with timeout for FPS control)
    if (WaitForSingleObject(m_frameEvent, 33) == WAIT_OBJECT_0) {
      // Read frame from shared memory
      int *header = static_cast<int *>(m_sharedMemoryPtr);
      int width = header[0];
      int height = header[1];

      if (width == VIDEO_WIDTH && height == VIDEO_HEIGHT) {
        BYTE *pixels = reinterpret_cast<BYTE *>(&header[4]);
        memcpy(pData, pixels,
               min(lDataLen, (long)(VIDEO_WIDTH * VIDEO_HEIGHT * 4)));
        hasFrame = true;
      }
    }
  }

  if (!hasFrame) {
    // Generate test pattern when no frame available
    GenerateTestPattern(pData, lDataLen);
  }

  // Set timestamps
  REFERENCE_TIME rtStart = m_frameNumber * FRAME_INTERVAL;
  REFERENCE_TIME rtStop = rtStart + FRAME_INTERVAL;

  pSample->SetTime(&rtStart, &rtStop);
  pSample->SetSyncPoint(TRUE);

  m_frameNumber++;

  // Sleep to maintain frame rate
  REFERENCE_TIME rtNow = 0;
  if (m_pFilter && m_pFilter->GetState(0, nullptr) == State_Running) {
    CRefTime now;
    m_pFilter->StreamTime(now);
    rtNow = now;
  }

  REFERENCE_TIME rtSleep = rtStart - rtNow;
  if (rtSleep > 0) {
    Sleep((DWORD)(rtSleep / 10000));
  }

  return S_OK;
}

void WebCAMOStream::GenerateTestPattern(BYTE *pData, long lDataLen) {
  // Generate a simple gradient test pattern
  for (int y = 0; y < VIDEO_HEIGHT; y++) {
    for (int x = 0; x < VIDEO_WIDTH; x++) {
      int offset = (y * VIDEO_WIDTH + x) * 4;
      if (offset + 3 < lDataLen) {
        pData[offset + 0] = (BYTE)((x + m_frameNumber) % 256); // B
        pData[offset + 1] = (BYTE)((y + m_frameNumber) % 256); // G
        pData[offset + 2] = 100;                               // R
        pData[offset + 3] = 255;                               // A
      }
    }
  }
}

bool WebCAMOStream::OpenSharedMemory() {
  m_sharedMemoryHandle =
      OpenFileMappingW(FILE_MAP_READ, FALSE, L"WebCAMO_SharedFrame");

  if (m_sharedMemoryHandle) {
    m_sharedMemoryPtr =
        MapViewOfFile(m_sharedMemoryHandle, FILE_MAP_READ, 0, 0, 0);
  }

  m_frameEvent = OpenEventW(SYNCHRONIZE, FALSE, L"WebCAMO_FrameEvent");

  return m_sharedMemoryPtr != nullptr;
}

void WebCAMOStream::CloseSharedMemory() {
  if (m_frameEvent) {
    CloseHandle(m_frameEvent);
    m_frameEvent = nullptr;
  }

  if (m_sharedMemoryPtr) {
    UnmapViewOfFile(m_sharedMemoryPtr);
    m_sharedMemoryPtr = nullptr;
  }

  if (m_sharedMemoryHandle) {
    CloseHandle(m_sharedMemoryHandle);
    m_sharedMemoryHandle = nullptr;
  }
}

// ============================================================================
// Filter Registration
// ============================================================================

const AMOVIESETUP_MEDIATYPE sudOutputPinTypes = {&MEDIATYPE_Video,
                                                 &MEDIASUBTYPE_RGB32};

const AMOVIESETUP_PIN sudOutputPin = {const_cast<LPWSTR>(L"Output"),
                                      FALSE, // Is rendered?
                                      TRUE,  // Is output?
                                      FALSE, // Can zero instances be created?
                                      FALSE, // Is there at most one instance?
                                      &CLSID_NULL,
                                      nullptr,
                                      1,
                                      &sudOutputPinTypes};

const AMOVIESETUP_FILTER sudWebCAMOFilter = {
    &CLSID_WebCAMOCamera, L"WebCAMO Camera", MERIT_DO_NOT_USE, 1,
    &sudOutputPin};

CFactoryTemplate g_Templates[] = {{L"WebCAMO Camera", &CLSID_WebCAMOCamera,
                                   WebCAMOSource::CreateInstance, nullptr,
                                   &sudWebCAMOFilter}};

int g_cTemplates = sizeof(g_Templates) / sizeof(g_Templates[0]);

// DLL exports
STDAPI DllRegisterServer() {
  // Register as a video capture device
  HRESULT hr = AMovieDllRegisterServer2(TRUE);
  if (FAILED(hr))
    return hr;

  // Add to video input devices category
  IFilterMapper2 *pFm = nullptr;
  hr = CoCreateInstance(CLSID_FilterMapper2, nullptr, CLSCTX_INPROC_SERVER,
                        IID_IFilterMapper2, (void **)&pFm);
  if (SUCCEEDED(hr)) {
    REGFILTER2 rf2;
    rf2.dwVersion = 1;
    rf2.dwMerit = MERIT_DO_NOT_USE;
    rf2.cPins = 1;
    rf2.rgPins = &sudOutputPin;

    hr = pFm->RegisterFilter(CLSID_WebCAMOCamera, L"WebCAMO Camera", nullptr,
                             &CLSID_VideoInputDeviceCategory, L"WebCAMO Camera",
                             &rf2);
    pFm->Release();
  }

  return hr;
}

STDAPI DllUnregisterServer() {
  HRESULT hr = AMovieDllRegisterServer2(FALSE);

  IFilterMapper2 *pFm = nullptr;
  if (SUCCEEDED(CoCreateInstance(CLSID_FilterMapper2, nullptr,
                                 CLSCTX_INPROC_SERVER, IID_IFilterMapper2,
                                 (void **)&pFm))) {
    pFm->UnregisterFilter(&CLSID_VideoInputDeviceCategory, L"WebCAMO Camera",
                          CLSID_WebCAMOCamera);
    pFm->Release();
  }

  return hr;
}

extern "C" BOOL WINAPI DllEntryPoint(HINSTANCE, ULONG, LPVOID);

BOOL WINAPI DllMain(HINSTANCE hInstance, DWORD dwReason, LPVOID lpReserved) {
  return DllEntryPoint(hInstance, dwReason, lpReserved);
}
