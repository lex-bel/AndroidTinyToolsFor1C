#ifndef __ADDINNATIVE_H__
#define __ADDINNATIVE_H__


#include "ComponentBase.h"
#include "AddInDefBase.h"
#include "IMemoryManager.h"
#include "BroadcastReceiver.h"
#include "BluetoothBarcodeScannerHandler.h"

#if defined(__ANDROID__) //MOBILE_PLATFORM_WINRT


#include "mobile.h"
#include "IAndroidComponentHelper.h"
#include "jnienv.h"
#include "BluetoothBarcodeScannerHandler.h"
#include <jni.h>

#endif

#include <array>
#include <string>


///////////////////////////////////////////////////////////////////////////////
// class CAddInNative
class CAddInNative : public IComponentBase
{
public:
    enum Props
    {
        ePropLast      // Always last
    };

    enum Methods
    {
		eMethVibrate = 0,
        eMethBeep = 1,
        eMethToast = 2,
        eMethStartBroadcastReceiver = 3,
        eMethGetBluetoothDevicesList = 4,
        eMethStartBluetoothScannerHandler = 5,
        eMethStopBluetoothScannerHandler = 6,
        eMethIsBluetoothScannerHandlerConnected = 7,
        eMethGetDeviceId = 8,
        eMethStartCameraBarcodeScanner = 9,
        eMethStopCameraBarcodeScanner = 10,
        eMethGenerateQrCodeBase64 = 11,
        eMethStartHttpServer = 12,
        eMethStopHttpServer = 13,
        eMethHttpServerRespond = 14,
        eMethLast      // Always last
    };

    CAddInNative(void);
    virtual ~CAddInNative() override;
    // IInitDoneBase
    virtual bool ADDIN_API Init(void*) override;
    virtual bool ADDIN_API setMemManager(void* mem) override;
    virtual long ADDIN_API GetInfo() override;
    virtual void ADDIN_API Done() override;
    // ILanguageExtenderBase
    virtual bool ADDIN_API RegisterExtensionAs(WCHAR_T**) override;
    virtual long ADDIN_API GetNProps() override;
    virtual long ADDIN_API FindProp(const WCHAR_T* wsPropName) override;
    virtual const WCHAR_T* ADDIN_API GetPropName(long lPropNum, long lPropAlias) override;
    virtual bool ADDIN_API GetPropVal(const long lPropNum, tVariant* pvarPropVal) override;
    virtual bool ADDIN_API SetPropVal(const long lPropNum, tVariant* varPropVal) override;
    virtual bool ADDIN_API IsPropReadable(const long lPropNum) override;
    virtual bool ADDIN_API IsPropWritable(const long lPropNum) override;
    virtual long ADDIN_API GetNMethods() override;
    virtual long ADDIN_API FindMethod(const WCHAR_T* wsMethodName) override;
    virtual const WCHAR_T* ADDIN_API GetMethodName(const long lMethodNum, 
                            const long lMethodAlias) override;
    virtual long ADDIN_API GetNParams(const long lMethodNum) override;
    virtual bool ADDIN_API GetParamDefValue(const long lMethodNum, const long lParamNum,
                            tVariant *pvarParamDefValue) override;
    virtual bool ADDIN_API HasRetVal(const long lMethodNum) override;
    virtual bool ADDIN_API CallAsProc(const long lMethodNum,
                    tVariant* paParams, const long lSizeArray) override;
    virtual bool ADDIN_API CallAsFunc(const long lMethodNum,
                tVariant* pvarRetValue, tVariant* paParams, const long lSizeArray) override;
    // LocaleBase
    virtual void ADDIN_API SetLocale(const WCHAR_T* loc) override;

    // UserLanguageBase
    virtual void ADDIN_API SetUserInterfaceLanguageCode(const WCHAR_T* lang) override;

    IAddInDefBaseEx      *m_iConnect;
    IMemoryManager     *m_iMemory;

private:
    //long findName(const char16_t* names[], const char16_t* name, const uint32_t size) const;
	void Vibrate(tVariant* paParams, const long lSizeArray);
    void Beep(tVariant* paParams, const long lSizeArray);
    void Toast(tVariant* paParams, const long lSizeArray);
    void StartBroadcastReceiver(tVariant *paParams, const long lSizeArray);
    void StartBluetoothScannerHandler(tVariant *paParams, const long lSizeArray);
    void GetBluetoothDevicesList( tVariant* pvarRetValue, tVariant *paParams, const long lSizeArray);
    void GetDeviceId(tVariant* pvarRetValue);
    void StartCameraBarcodeScanner(tVariant* paParams, const long lSizeArray);
    void StopCameraBarcodeScanner();
    void GenerateQrCodeBase64(tVariant* pvarRetValue, tVariant* paParams, const long lSizeArray);
    void StartHttpServer(tVariant* pvarRetValue, tVariant* paParams, const long lSizeArray);
    void StopHttpServer();
    void HttpServerRespond(tVariant* pvarRetValue, tVariant* paParams, const long lSizeArray);
    void StopBroadcastReceiver();

    BroadcastReceiver broadcastReceiver;
    BluetoothBarcodeScannerHandler bluetoothBarcodeScannerHandler;
};

#endif //__ADDINNATIVE_H__
