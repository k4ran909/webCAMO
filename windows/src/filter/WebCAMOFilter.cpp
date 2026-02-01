/**
 * WebCAMO Virtual Camera - Standalone DirectShow Filter
 * ======================================================
 * Self-contained implementation without DirectShow Base Classes
 *
 * Build: cl /LD /EHsc /O2 WebCAMOFilter.cpp /link strmiids.lib ole32.lib
 * oleaut32.lib uuid.lib /DEF:WebCAMOFilter.def Register: regsvr32
 * WebCAMOFilter.dll Unregister: regsvr32 /u WebCAMOFilter.dll
 */

#define WIN32_LEAN_AND_MEAN
#define INITGUID

#include <dshow.h>
#include <dvdmedia.h>
#include <olectl.h>
#include <strsafe.h>
#include <windows.h>

// === Constants ===
static const int VIDEO_WIDTH = 1280;
static const int VIDEO_HEIGHT = 720;
static const int VIDEO_FPS = 30;
static const REFERENCE_TIME FRAME_INTERVAL = 10000000LL / VIDEO_FPS;
static const DWORD FRAME_SIZE = VIDEO_WIDTH * VIDEO_HEIGHT * 4;

// === GUIDs ===
// {E8F2A3B4-5C6D-7E8F-9A0B-C1D2E3F4A5B6}
DEFINE_GUID(CLSID_WebCAMO, 0xe8f2a3b4, 0x5c6d, 0x7e8f, 0x9a, 0x0b, 0xc1, 0xd2,
            0xe3, 0xf4, 0xa5, 0xb6);

static HINSTANCE g_hInstance = NULL;
static LONG g_cRefAll = 0;

// Forward declarations
class WebCAMOPin;
class WebCAMOFilter;

// === Utility ===
void IncrementRefCount() { InterlockedIncrement(&g_cRefAll); }
void DecrementRefCount() { InterlockedDecrement(&g_cRefAll); }

// ============================================================================
// WebCAMOPin - Output Pin
// ============================================================================
class WebCAMOPin : public IPin, public IMemInputPin, public IKsPropertySet {
public:
  LONG m_cRef;
  WebCAMOFilter *m_pFilter;
  IPin *m_pConnectedPin;
  IMemAllocator *m_pAllocator;
  AM_MEDIA_TYPE m_mt;

  // Shared memory for receiving frames
  HANDLE m_hMapping;
  void *m_pMapped;
  HANDLE m_hEvent;

  // Streaming
  HANDLE m_hThread;
  volatile bool m_bRunning;
  LONGLONG m_llFrameNumber;
  CRITICAL_SECTION m_cs;

  WebCAMOPin(WebCAMOFilter *pFilter);
  ~WebCAMOPin();

  // IUnknown
  STDMETHODIMP QueryInterface(REFIID riid, void **ppv);
  STDMETHODIMP_(ULONG) AddRef() { return InterlockedIncrement(&m_cRef); }
  STDMETHODIMP_(ULONG) Release();

  // IPin
  STDMETHODIMP Connect(IPin *pReceivePin, const AM_MEDIA_TYPE *pmt);
  STDMETHODIMP ReceiveConnection(IPin *pConnector, const AM_MEDIA_TYPE *pmt) {
    return E_UNEXPECTED;
  }
  STDMETHODIMP Disconnect();
  STDMETHODIMP ConnectedTo(IPin **pPin);
  STDMETHODIMP ConnectionMediaType(AM_MEDIA_TYPE *pmt);
  STDMETHODIMP QueryPinInfo(PIN_INFO *pInfo);
  STDMETHODIMP QueryDirection(PIN_DIRECTION *pPinDir) {
    *pPinDir = PINDIR_OUTPUT;
    return S_OK;
  }
  STDMETHODIMP QueryId(LPWSTR *Id);
  STDMETHODIMP QueryAccept(const AM_MEDIA_TYPE *pmt);
  STDMETHODIMP EnumMediaTypes(IEnumMediaTypes **ppEnum);
  STDMETHODIMP QueryInternalConnections(IPin **apPin, ULONG *nPin) {
    return E_NOTIMPL;
  }
  STDMETHODIMP EndOfStream() { return S_OK; }
  STDMETHODIMP BeginFlush() { return S_OK; }
  STDMETHODIMP EndFlush() { return S_OK; }
  STDMETHODIMP NewSegment(REFERENCE_TIME tStart, REFERENCE_TIME tStop,
                          double dRate) {
    return S_OK;
  }

  // IMemInputPin (not used but needed for some apps)
  STDMETHODIMP GetAllocator(IMemAllocator **ppAllocator) {
    return VFW_E_NO_ALLOCATOR;
  }
  STDMETHODIMP NotifyAllocator(IMemAllocator *pAllocator, BOOL bReadOnly) {
    return S_OK;
  }
  STDMETHODIMP GetAllocatorRequirements(ALLOCATOR_PROPERTIES *pProps) {
    return E_NOTIMPL;
  }
  STDMETHODIMP Receive(IMediaSample *pSample) { return E_UNEXPECTED; }
  STDMETHODIMP ReceiveMultiple(IMediaSample **pSamples, long nSamples,
                               long *nSamplesProcessed) {
    return E_UNEXPECTED;
  }
  STDMETHODIMP ReceiveCanBlock() { return S_FALSE; }

  // IKsPropertySet (for device enumeration compatibility)
  STDMETHODIMP Set(REFGUID guidPropSet, DWORD dwPropID, LPVOID pInstanceData,
                   DWORD cbInstanceData, LPVOID pPropData, DWORD cbPropData) {
    return E_NOTIMPL;
  }
  STDMETHODIMP Get(REFGUID guidPropSet, DWORD dwPropID, LPVOID pInstanceData,
                   DWORD cbInstanceData, LPVOID pPropData, DWORD cbPropData,
                   DWORD *pcbReturned);
  STDMETHODIMP QuerySupported(REFGUID guidPropSet, DWORD dwPropID,
                              DWORD *pTypeSupport);

  // Internal
  HRESULT SetMediaType();
  void GenerateFrame(BYTE *pData);
  bool OpenSharedMemory();
  void CloseSharedMemory();
  void Start();
  void Stop();
  static DWORD WINAPI ThreadProc(LPVOID pv);
};

// ============================================================================
// WebCAMOFilter - Source Filter
// ============================================================================
class WebCAMOFilter : public IBaseFilter, public IAMFilterMiscFlags {
public:
  LONG m_cRef;
  IFilterGraph *m_pGraph;
  FILTER_STATE m_State;
  IReferenceClock *m_pClock;
  WebCAMOPin *m_pPin;
  CRITICAL_SECTION m_cs;

  WebCAMOFilter();
  ~WebCAMOFilter();

  // IUnknown
  STDMETHODIMP QueryInterface(REFIID riid, void **ppv);
  STDMETHODIMP_(ULONG) AddRef() { return InterlockedIncrement(&m_cRef); }
  STDMETHODIMP_(ULONG) Release();

  // IPersist
  STDMETHODIMP GetClassID(CLSID *pClsID) {
    *pClsID = CLSID_WebCAMO;
    return S_OK;
  }

  // IMediaFilter
  STDMETHODIMP Stop();
  STDMETHODIMP Pause();
  STDMETHODIMP Run(REFERENCE_TIME tStart);
  STDMETHODIMP GetState(DWORD dwMilliSecsTimeout, FILTER_STATE *State) {
    *State = m_State;
    return S_OK;
  }
  STDMETHODIMP SetSyncSource(IReferenceClock *pClock);
  STDMETHODIMP GetSyncSource(IReferenceClock **pClock);

  // IBaseFilter
  STDMETHODIMP EnumPins(IEnumPins **ppEnum);
  STDMETHODIMP FindPin(LPCWSTR Id, IPin **ppPin);
  STDMETHODIMP QueryFilterInfo(FILTER_INFO *pInfo);
  STDMETHODIMP JoinFilterGraph(IFilterGraph *pGraph, LPCWSTR pName);
  STDMETHODIMP QueryVendorInfo(LPWSTR *pVendorInfo) { return E_NOTIMPL; }

  // IAMFilterMiscFlags
  STDMETHODIMP_(ULONG) GetMiscFlags() { return AM_FILTER_MISC_FLAGS_IS_SOURCE; }
};

// ============================================================================
// WebCAMOPin Implementation
// ============================================================================

WebCAMOPin::WebCAMOPin(WebCAMOFilter *pFilter)
    : m_cRef(1), m_pFilter(pFilter), m_pConnectedPin(NULL), m_pAllocator(NULL),
      m_hMapping(NULL), m_pMapped(NULL), m_hEvent(NULL), m_hThread(NULL),
      m_bRunning(false), m_llFrameNumber(0) {

  InitializeCriticalSection(&m_cs);
  ZeroMemory(&m_mt, sizeof(m_mt));
  SetMediaType();
  IncrementRefCount();
}

WebCAMOPin::~WebCAMOPin() {
  Stop();
  CloseSharedMemory();
  if (m_pConnectedPin)
    m_pConnectedPin->Release();
  if (m_pAllocator)
    m_pAllocator->Release();
  if (m_mt.pbFormat)
    CoTaskMemFree(m_mt.pbFormat);
  DeleteCriticalSection(&m_cs);
  DecrementRefCount();
}

STDMETHODIMP WebCAMOPin::QueryInterface(REFIID riid, void **ppv) {
  if (riid == IID_IUnknown || riid == IID_IPin) {
    *ppv = static_cast<IPin *>(this);
  } else if (riid == IID_IKsPropertySet) {
    *ppv = static_cast<IKsPropertySet *>(this);
  } else {
    *ppv = NULL;
    return E_NOINTERFACE;
  }
  AddRef();
  return S_OK;
}

STDMETHODIMP_(ULONG) WebCAMOPin::Release() {
  LONG cRef = InterlockedDecrement(&m_cRef);
  if (cRef == 0)
    delete this;
  return cRef;
}

HRESULT WebCAMOPin::SetMediaType() {
  m_mt.majortype = MEDIATYPE_Video;
  m_mt.subtype = MEDIASUBTYPE_RGB32;
  m_mt.bFixedSizeSamples = TRUE;
  m_mt.bTemporalCompression = FALSE;
  m_mt.lSampleSize = FRAME_SIZE;
  m_mt.formattype = FORMAT_VideoInfo;
  m_mt.cbFormat = sizeof(VIDEOINFOHEADER);
  m_mt.pbFormat = (BYTE *)CoTaskMemAlloc(sizeof(VIDEOINFOHEADER));

  VIDEOINFOHEADER *pvi = (VIDEOINFOHEADER *)m_mt.pbFormat;
  ZeroMemory(pvi, sizeof(VIDEOINFOHEADER));
  pvi->bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
  pvi->bmiHeader.biWidth = VIDEO_WIDTH;
  pvi->bmiHeader.biHeight = VIDEO_HEIGHT;
  pvi->bmiHeader.biPlanes = 1;
  pvi->bmiHeader.biBitCount = 32;
  pvi->bmiHeader.biCompression = BI_RGB;
  pvi->bmiHeader.biSizeImage = FRAME_SIZE;
  pvi->AvgTimePerFrame = FRAME_INTERVAL;

  return S_OK;
}

STDMETHODIMP WebCAMOPin::Connect(IPin *pReceivePin, const AM_MEDIA_TYPE *pmt) {
  if (m_pConnectedPin)
    return VFW_E_ALREADY_CONNECTED;

  // Try our media type
  HRESULT hr = pReceivePin->ReceiveConnection(this, &m_mt);
  if (FAILED(hr))
    return hr;

  m_pConnectedPin = pReceivePin;
  m_pConnectedPin->AddRef();

  // Get allocator
  IMemInputPin *pMemInput = NULL;
  if (SUCCEEDED(
          pReceivePin->QueryInterface(IID_IMemInputPin, (void **)&pMemInput))) {
    if (FAILED(pMemInput->GetAllocator(&m_pAllocator))) {
      // Create our own allocator
      CoCreateInstance(CLSID_MemoryAllocator, NULL, CLSCTX_INPROC_SERVER,
                       IID_IMemAllocator, (void **)&m_pAllocator);
    }

    if (m_pAllocator) {
      ALLOCATOR_PROPERTIES props = {4, (long)FRAME_SIZE, 1, 0};
      ALLOCATOR_PROPERTIES actual;
      m_pAllocator->SetProperties(&props, &actual);
      pMemInput->NotifyAllocator(m_pAllocator, FALSE);
    }
    pMemInput->Release();
  }

  OpenSharedMemory();
  return S_OK;
}

STDMETHODIMP WebCAMOPin::Disconnect() {
  if (m_pConnectedPin) {
    m_pConnectedPin->Release();
    m_pConnectedPin = NULL;
  }
  if (m_pAllocator) {
    m_pAllocator->Release();
    m_pAllocator = NULL;
  }
  CloseSharedMemory();
  return S_OK;
}

STDMETHODIMP WebCAMOPin::ConnectedTo(IPin **pPin) {
  if (!m_pConnectedPin)
    return VFW_E_NOT_CONNECTED;
  *pPin = m_pConnectedPin;
  m_pConnectedPin->AddRef();
  return S_OK;
}

STDMETHODIMP WebCAMOPin::ConnectionMediaType(AM_MEDIA_TYPE *pmt) {
  if (!m_pConnectedPin)
    return VFW_E_NOT_CONNECTED;
  *pmt = m_mt;
  if (m_mt.pbFormat) {
    pmt->pbFormat = (BYTE *)CoTaskMemAlloc(m_mt.cbFormat);
    memcpy(pmt->pbFormat, m_mt.pbFormat, m_mt.cbFormat);
  }
  return S_OK;
}

STDMETHODIMP WebCAMOPin::QueryPinInfo(PIN_INFO *pInfo) {
  pInfo->pFilter = (IBaseFilter *)m_pFilter;
  m_pFilter->AddRef();
  pInfo->dir = PINDIR_OUTPUT;
  StringCchCopyW(pInfo->achName, MAX_PIN_NAME, L"Video");
  return S_OK;
}

STDMETHODIMP WebCAMOPin::QueryId(LPWSTR *Id) {
  *Id = (LPWSTR)CoTaskMemAlloc(16);
  StringCchCopyW(*Id, 8, L"Video");
  return S_OK;
}

STDMETHODIMP WebCAMOPin::QueryAccept(const AM_MEDIA_TYPE *pmt) {
  if (pmt->majortype != MEDIATYPE_Video)
    return S_FALSE;
  if (pmt->subtype != MEDIASUBTYPE_RGB32)
    return S_FALSE;
  return S_OK;
}

// Simple media type enumerator
class MediaTypeEnum : public IEnumMediaTypes {
  LONG m_cRef;
  int m_pos;
  AM_MEDIA_TYPE m_mt;

public:
  MediaTypeEnum(const AM_MEDIA_TYPE &mt) : m_cRef(1), m_pos(0), m_mt(mt) {
    if (mt.pbFormat) {
      m_mt.pbFormat = (BYTE *)CoTaskMemAlloc(mt.cbFormat);
      memcpy(m_mt.pbFormat, mt.pbFormat, mt.cbFormat);
    }
  }
  ~MediaTypeEnum() {
    if (m_mt.pbFormat)
      CoTaskMemFree(m_mt.pbFormat);
  }

  STDMETHODIMP QueryInterface(REFIID riid, void **ppv) {
    if (riid == IID_IUnknown || riid == IID_IEnumMediaTypes) {
      *ppv = this;
      AddRef();
      return S_OK;
    }
    return E_NOINTERFACE;
  }
  STDMETHODIMP_(ULONG) AddRef() { return InterlockedIncrement(&m_cRef); }
  STDMETHODIMP_(ULONG) Release() {
    LONG c = InterlockedDecrement(&m_cRef);
    if (c == 0)
      delete this;
    return c;
  }
  STDMETHODIMP Next(ULONG cmt, AM_MEDIA_TYPE **ppmt, ULONG *pcFetched) {
    if (m_pos > 0) {
      if (pcFetched)
        *pcFetched = 0;
      return S_FALSE;
    }
    ppmt[0] = (AM_MEDIA_TYPE *)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
    *ppmt[0] = m_mt;
    if (m_mt.pbFormat) {
      ppmt[0]->pbFormat = (BYTE *)CoTaskMemAlloc(m_mt.cbFormat);
      memcpy(ppmt[0]->pbFormat, m_mt.pbFormat, m_mt.cbFormat);
    }
    m_pos++;
    if (pcFetched)
      *pcFetched = 1;
    return S_OK;
  }
  STDMETHODIMP Skip(ULONG cmt) {
    m_pos += cmt;
    return S_OK;
  }
  STDMETHODIMP Reset() {
    m_pos = 0;
    return S_OK;
  }
  STDMETHODIMP Clone(IEnumMediaTypes **ppEnum) {
    *ppEnum = new MediaTypeEnum(m_mt);
    return S_OK;
  }
};

STDMETHODIMP WebCAMOPin::EnumMediaTypes(IEnumMediaTypes **ppEnum) {
  *ppEnum = new MediaTypeEnum(m_mt);
  return S_OK;
}

// KSPROPSETID_Pin for device enumeration
static const GUID KSPROPSETID_Pin = {
    0x8C134960,
    0x51AD,
    0x11CF,
    {0x87, 0x8A, 0x94, 0xF8, 0x01, 0xC1, 0x00, 0x00}};

STDMETHODIMP WebCAMOPin::Get(REFGUID guidPropSet, DWORD dwPropID,
                             LPVOID pInstanceData, DWORD cbInstanceData,
                             LPVOID pPropData, DWORD cbPropData,
                             DWORD *pcbReturned) {
  if (guidPropSet == AMPROPSETID_Pin && dwPropID == AMPROPERTY_PIN_CATEGORY) {
    if (cbPropData < sizeof(GUID))
      return E_INVALIDARG;
    *(GUID *)pPropData = PIN_CATEGORY_CAPTURE;
    if (pcbReturned)
      *pcbReturned = sizeof(GUID);
    return S_OK;
  }
  return E_NOTIMPL;
}

STDMETHODIMP WebCAMOPin::QuerySupported(REFGUID guidPropSet, DWORD dwPropID,
                                        DWORD *pTypeSupport) {
  if (guidPropSet == AMPROPSETID_Pin && dwPropID == AMPROPERTY_PIN_CATEGORY) {
    *pTypeSupport = KSPROPERTY_SUPPORT_GET;
    return S_OK;
  }
  return E_NOTIMPL;
}

bool WebCAMOPin::OpenSharedMemory() {
  m_hMapping = OpenFileMappingW(FILE_MAP_READ, FALSE, L"WebCAMO_SharedFrame");
  if (m_hMapping) {
    m_pMapped = MapViewOfFile(m_hMapping, FILE_MAP_READ, 0, 0, 0);
  }
  m_hEvent = OpenEventW(SYNCHRONIZE, FALSE, L"WebCAMO_FrameEvent");
  return m_pMapped != NULL;
}

void WebCAMOPin::CloseSharedMemory() {
  if (m_pMapped) {
    UnmapViewOfFile(m_pMapped);
    m_pMapped = NULL;
  }
  if (m_hMapping) {
    CloseHandle(m_hMapping);
    m_hMapping = NULL;
  }
  if (m_hEvent) {
    CloseHandle(m_hEvent);
    m_hEvent = NULL;
  }
}

void WebCAMOPin::GenerateFrame(BYTE *pData) {
  bool hasFrame = false;

  // Try reading from shared memory
  if (m_pMapped && m_hEvent) {
    if (WaitForSingleObject(m_hEvent, 0) == WAIT_OBJECT_0) {
      int *header = (int *)m_pMapped;
      if (header[0] == VIDEO_WIDTH && header[1] == VIDEO_HEIGHT) {
        memcpy(pData, (BYTE *)&header[4], FRAME_SIZE);
        hasFrame = true;
      }
    }
  }

  if (!hasFrame) {
    // Show "Waiting for WebCAMO" pattern
    for (int y = 0; y < VIDEO_HEIGHT; y++) {
      for (int x = 0; x < VIDEO_WIDTH; x++) {
        int i = (y * VIDEO_WIDTH + x) * 4;
        // Dark gradient background
        pData[i + 0] = (BYTE)(50 + (x * 30 / VIDEO_WIDTH));  // B
        pData[i + 1] = (BYTE)(50 + (y * 30 / VIDEO_HEIGHT)); // G
        pData[i + 2] = (BYTE)(80);                           // R
        pData[i + 3] = 255;                                  // A
      }
    }
  }
  m_llFrameNumber++;
}

void WebCAMOPin::Start() {
  if (m_bRunning)
    return;
  m_bRunning = true;
  if (m_pAllocator)
    m_pAllocator->Commit();
  m_hThread = CreateThread(NULL, 0, ThreadProc, this, 0, NULL);
}

void WebCAMOPin::Stop() {
  if (!m_bRunning)
    return;
  m_bRunning = false;
  if (m_hThread) {
    WaitForSingleObject(m_hThread, 1000);
    CloseHandle(m_hThread);
    m_hThread = NULL;
  }
  if (m_pAllocator)
    m_pAllocator->Decommit();
}

DWORD WINAPI WebCAMOPin::ThreadProc(LPVOID pv) {
  WebCAMOPin *pPin = (WebCAMOPin *)pv;

  IMemInputPin *pMemInput = NULL;
  if (pPin->m_pConnectedPin) {
    pPin->m_pConnectedPin->QueryInterface(IID_IMemInputPin,
                                          (void **)&pMemInput);
  }

  DWORD frameTimeMs = 1000 / VIDEO_FPS;

  while (pPin->m_bRunning) {
    DWORD startTime = GetTickCount();

    if (pPin->m_pAllocator && pMemInput) {
      IMediaSample *pSample = NULL;
      if (SUCCEEDED(pPin->m_pAllocator->GetBuffer(&pSample, NULL, NULL, 0))) {
        BYTE *pData = NULL;
        if (SUCCEEDED(pSample->GetPointer(&pData))) {
          pPin->GenerateFrame(pData);
          pSample->SetActualDataLength(FRAME_SIZE);

          REFERENCE_TIME rtStart = pPin->m_llFrameNumber * FRAME_INTERVAL;
          REFERENCE_TIME rtStop = rtStart + FRAME_INTERVAL;
          pSample->SetTime(&rtStart, &rtStop);
          pSample->SetSyncPoint(TRUE);

          pMemInput->Receive(pSample);
        }
        pSample->Release();
      }
    }

    // Maintain frame rate
    DWORD elapsed = GetTickCount() - startTime;
    if (elapsed < frameTimeMs) {
      Sleep(frameTimeMs - elapsed);
    }
  }

  if (pMemInput)
    pMemInput->Release();
  return 0;
}

// ============================================================================
// WebCAMOFilter Implementation
// ============================================================================

WebCAMOFilter::WebCAMOFilter()
    : m_cRef(1), m_pGraph(NULL), m_State(State_Stopped), m_pClock(NULL) {
  InitializeCriticalSection(&m_cs);
  m_pPin = new WebCAMOPin(this);
  IncrementRefCount();
}

WebCAMOFilter::~WebCAMOFilter() {
  if (m_pPin) {
    m_pPin->Release();
  }
  if (m_pClock)
    m_pClock->Release();
  DeleteCriticalSection(&m_cs);
  DecrementRefCount();
}

STDMETHODIMP WebCAMOFilter::QueryInterface(REFIID riid, void **ppv) {
  if (riid == IID_IUnknown || riid == IID_IPersist ||
      riid == IID_IMediaFilter || riid == IID_IBaseFilter) {
    *ppv = static_cast<IBaseFilter *>(this);
  } else if (riid == IID_IAMFilterMiscFlags) {
    *ppv = static_cast<IAMFilterMiscFlags *>(this);
  } else {
    *ppv = NULL;
    return E_NOINTERFACE;
  }
  AddRef();
  return S_OK;
}

STDMETHODIMP_(ULONG) WebCAMOFilter::Release() {
  LONG cRef = InterlockedDecrement(&m_cRef);
  if (cRef == 0)
    delete this;
  return cRef;
}

STDMETHODIMP WebCAMOFilter::Stop() {
  EnterCriticalSection(&m_cs);
  m_pPin->Stop();
  m_State = State_Stopped;
  LeaveCriticalSection(&m_cs);
  return S_OK;
}

STDMETHODIMP WebCAMOFilter::Pause() {
  EnterCriticalSection(&m_cs);
  if (m_State == State_Stopped) {
    m_pPin->Start();
  }
  m_State = State_Paused;
  LeaveCriticalSection(&m_cs);
  return S_OK;
}

STDMETHODIMP WebCAMOFilter::Run(REFERENCE_TIME tStart) {
  EnterCriticalSection(&m_cs);
  if (m_State == State_Stopped) {
    m_pPin->Start();
  }
  m_State = State_Running;
  LeaveCriticalSection(&m_cs);
  return S_OK;
}

STDMETHODIMP WebCAMOFilter::SetSyncSource(IReferenceClock *pClock) {
  if (m_pClock)
    m_pClock->Release();
  m_pClock = pClock;
  if (m_pClock)
    m_pClock->AddRef();
  return S_OK;
}

STDMETHODIMP WebCAMOFilter::GetSyncSource(IReferenceClock **pClock) {
  *pClock = m_pClock;
  if (m_pClock)
    m_pClock->AddRef();
  return S_OK;
}

// Simple pin enumerator
class PinEnum : public IEnumPins {
  LONG m_cRef;
  int m_pos;
  WebCAMOPin *m_pPin;

public:
  PinEnum(WebCAMOPin *pPin) : m_cRef(1), m_pos(0), m_pPin(pPin) {
    m_pPin->AddRef();
  }
  ~PinEnum() { m_pPin->Release(); }

  STDMETHODIMP QueryInterface(REFIID riid, void **ppv) {
    if (riid == IID_IUnknown || riid == IID_IEnumPins) {
      *ppv = this;
      AddRef();
      return S_OK;
    }
    return E_NOINTERFACE;
  }
  STDMETHODIMP_(ULONG) AddRef() { return InterlockedIncrement(&m_cRef); }
  STDMETHODIMP_(ULONG) Release() {
    LONG c = InterlockedDecrement(&m_cRef);
    if (c == 0)
      delete this;
    return c;
  }
  STDMETHODIMP Next(ULONG cPins, IPin **ppPins, ULONG *pcFetched) {
    if (m_pos > 0) {
      if (pcFetched)
        *pcFetched = 0;
      return S_FALSE;
    }
    ppPins[0] = m_pPin;
    m_pPin->AddRef();
    m_pos++;
    if (pcFetched)
      *pcFetched = 1;
    return S_OK;
  }
  STDMETHODIMP Skip(ULONG cPins) {
    m_pos += cPins;
    return S_OK;
  }
  STDMETHODIMP Reset() {
    m_pos = 0;
    return S_OK;
  }
  STDMETHODIMP Clone(IEnumPins **ppEnum) {
    *ppEnum = new PinEnum(m_pPin);
    return S_OK;
  }
};

STDMETHODIMP WebCAMOFilter::EnumPins(IEnumPins **ppEnum) {
  *ppEnum = new PinEnum(m_pPin);
  return S_OK;
}

STDMETHODIMP WebCAMOFilter::FindPin(LPCWSTR Id, IPin **ppPin) {
  *ppPin = m_pPin;
  m_pPin->AddRef();
  return S_OK;
}

STDMETHODIMP WebCAMOFilter::QueryFilterInfo(FILTER_INFO *pInfo) {
  StringCchCopyW(pInfo->achName, MAX_FILTER_NAME, L"WebCAMO");
  pInfo->pGraph = m_pGraph;
  if (m_pGraph)
    m_pGraph->AddRef();
  return S_OK;
}

STDMETHODIMP WebCAMOFilter::JoinFilterGraph(IFilterGraph *pGraph,
                                            LPCWSTR pName) {
  m_pGraph = pGraph; // Don't AddRef - prevent circular reference
  return S_OK;
}

// ============================================================================
// Class Factory
// ============================================================================

class WebCAMOFactory : public IClassFactory {
  LONG m_cRef;

public:
  WebCAMOFactory() : m_cRef(1) { IncrementRefCount(); }
  ~WebCAMOFactory() { DecrementRefCount(); }

  STDMETHODIMP QueryInterface(REFIID riid, void **ppv) {
    if (riid == IID_IUnknown || riid == IID_IClassFactory) {
      *ppv = this;
      AddRef();
      return S_OK;
    }
    return E_NOINTERFACE;
  }
  STDMETHODIMP_(ULONG) AddRef() { return InterlockedIncrement(&m_cRef); }
  STDMETHODIMP_(ULONG) Release() {
    LONG c = InterlockedDecrement(&m_cRef);
    if (c == 0)
      delete this;
    return c;
  }
  STDMETHODIMP CreateInstance(IUnknown *pOuter, REFIID riid, void **ppv) {
    if (pOuter)
      return CLASS_E_NOAGGREGATION;
    WebCAMOFilter *pFilter = new WebCAMOFilter();
    HRESULT hr = pFilter->QueryInterface(riid, ppv);
    pFilter->Release();
    return hr;
  }
  STDMETHODIMP LockServer(BOOL fLock) { return S_OK; }
};

// ============================================================================
// DLL Exports
// ============================================================================

extern "C" {

BOOL WINAPI DllMain(HINSTANCE hInstance, DWORD dwReason, LPVOID lpReserved) {
  g_hInstance = hInstance;
  return TRUE;
}

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void **ppv) {
  if (rclsid != CLSID_WebCAMO)
    return CLASS_E_CLASSNOTAVAILABLE;
  WebCAMOFactory *pFactory = new WebCAMOFactory();
  HRESULT hr = pFactory->QueryInterface(riid, ppv);
  pFactory->Release();
  return hr;
}

STDAPI DllCanUnloadNow() { return (g_cRefAll == 0) ? S_OK : S_FALSE; }

STDAPI DllRegisterServer() {
  // Register CLSID
  WCHAR szModule[MAX_PATH];
  GetModuleFileNameW(g_hInstance, szModule, MAX_PATH);

  HKEY hKey;
  WCHAR szKey[256];

  // CLSID entry
  StringCchPrintfW(szKey, 256,
                   L"CLSID\\{E8F2A3B4-5C6D-7E8F-9A0B-C1D2E3F4A5B6}");
  RegCreateKeyExW(HKEY_CLASSES_ROOT, szKey, 0, NULL, 0, KEY_WRITE, NULL, &hKey,
                  NULL);
  RegSetValueExW(hKey, NULL, 0, REG_SZ, (BYTE *)L"WebCAMO", 16);
  RegCloseKey(hKey);

  StringCchPrintfW(
      szKey, 256,
      L"CLSID\\{E8F2A3B4-5C6D-7E8F-9A0B-C1D2E3F4A5B6}\\InprocServer32");
  RegCreateKeyExW(HKEY_CLASSES_ROOT, szKey, 0, NULL, 0, KEY_WRITE, NULL, &hKey,
                  NULL);
  RegSetValueExW(hKey, NULL, 0, REG_SZ, (BYTE *)szModule,
                 (DWORD)((wcslen(szModule) + 1) * sizeof(WCHAR)));
  RegSetValueExW(hKey, L"ThreadingModel", 0, REG_SZ, (BYTE *)L"Both", 10);
  RegCloseKey(hKey);

  // Register as video input device
  IFilterMapper2 *pFm = NULL;
  HRESULT hr = CoInitialize(NULL);
  if (SUCCEEDED(CoCreateInstance(CLSID_FilterMapper2, NULL,
                                 CLSCTX_INPROC_SERVER, IID_IFilterMapper2,
                                 (void **)&pFm))) {
    REGPINTYPES pinTypes = {&MEDIATYPE_Video, &MEDIASUBTYPE_RGB32};
    REGFILTERPINS rgPins = {L"Video", FALSE, TRUE, FALSE,    FALSE,
                            NULL,     NULL,  1,    &pinTypes};
    REGFILTER2 rf2;
    rf2.dwVersion = 1; // Use version 1 with REGFILTERPINS
    rf2.dwMerit = MERIT_DO_NOT_USE + 1;
    rf2.cPins = 1;
    rf2.rgPins = &rgPins;

    pFm->RegisterFilter(CLSID_WebCAMO, L"WebCAMO", NULL,
                        &CLSID_VideoInputDeviceCategory, L"WebCAMO", &rf2);
    pFm->Release();
  }
  CoUninitialize();

  return S_OK;
}

STDAPI DllUnregisterServer() {
  // Unregister from video input devices
  CoInitialize(NULL);
  IFilterMapper2 *pFm = NULL;
  if (SUCCEEDED(CoCreateInstance(CLSID_FilterMapper2, NULL,
                                 CLSCTX_INPROC_SERVER, IID_IFilterMapper2,
                                 (void **)&pFm))) {
    pFm->UnregisterFilter(&CLSID_VideoInputDeviceCategory, L"WebCAMO",
                          CLSID_WebCAMO);
    pFm->Release();
  }
  CoUninitialize();

  // Remove CLSID entries
  RegDeleteTreeW(HKEY_CLASSES_ROOT,
                 L"CLSID\\{E8F2A3B4-5C6D-7E8F-9A0B-C1D2E3F4A5B6}");

  return S_OK;
}

} // extern "C"
