package com.example.collathon.voicerecognition;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.google.gson.Gson;

import android.util.Base64;
import android.widget.Toast;

import org.apache.commons.lang.StringEscapeUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// ETRI AI Open API 中 음성인식 API 사용
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    public static final String PREFS_NAME = "prefs";
    private static final String MSG_KEY = "PUSH YOUR KEY";
    public final int MY_PERMISSIONS_REQUEST_READ_CONTACTS = 1;
    Button buttonStart;
    TextView textResult;
    Spinner spinnerMode;
    String curMode;
    String result;
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public synchronized void handleMessage(Message msg) {
            Bundle bd = msg.getData();
            String v = bd.getString(MSG_KEY);
            switch (msg.what) {
                // 녹음이 시작되었음(버튼)
                case 1:
                    textResult.setText(v);
                    buttonStart.setText("PUSH TO STOP");
                    break;
                // 녹음이 정상적으로 x종료되었음(버튼 또는 ma time)
                case 2:
                    textResult.setText(v);
                    buttonStart.setEnabled(false);
                    break;
                // 녹음이 비정상적으로 종료되었음(마이크 권한 등)
                case 3:
                    textResult.setText(v);
                    buttonStart.setText("PUSH TO START");
                    break;
                // 인식이 비정상적으로 종료되었음(timeout 등)
                case 4:
                    textResult.setText(v);
                    buttonStart.setEnabled(true);
                    buttonStart.setText("PUSH TO START");
                    break;
                // 인식이 정상적으로 종료되었음 (thread내에서 exception포함)
                case 5:
                    textResult.setText(StringEscapeUtils.unescapeJava(result));
                    buttonStart.setEnabled(true);
                    buttonStart.setText("PUSH TO START");
                    break;
            }
            super.handleMessage(msg);
        }
    };
    int maxLenSpeech = 16000 * 45;
    byte[] speechData = new byte[maxLenSpeech * 2];
    int lenSpeech = 0;
    boolean isRecording = false;
    boolean forceStop = false;
    //체크할 권한 배열
    String[] permission_list = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public static String readStream(InputStream in) throws IOException, JSONException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in),1000);
        for (String line = r.readLine(); line != null; line =r.readLine()){
            sb.append(line);
        }
        in.close();

        String rec_data = sb.toString();
        JSONObject jsonObject = new JSONObject(rec_data);
        Log.i(TAG,"서버에서 받아온 DATA = "+rec_data);
        jsonObject = jsonObject.getJSONObject("return_object");
        Log.i(TAG,"서버에서 받아온 DATA -> JSONObject로 추출 = "+jsonObject.toString());
        String resultText = jsonObject.getString("recognized");
        Log.i(TAG,"서버에서 받아온 DATA -> String으로만 추출 = "+resultText);

        return resultText;
    }

    public void SendMessage(String str, int id) {
        Message msg = handler.obtainMessage();
        Bundle bd = new Bundle();
        bd.putString(MSG_KEY, str);
        msg.what = id;
        msg.setData(bd);
        handler.sendMessage(msg);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buttonStart = (Button) findViewById(R.id.buttonStart);
        textResult = (TextView) findViewById(R.id.textResult);
        spinnerMode = (Spinner) findViewById(R.id.spinnerMode);

        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);

        ArrayList<String> modeArr = new ArrayList<>();
        modeArr.add("한국어인식");
        modeArr.add("영어인식");
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, modeArr);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(modeAdapter);
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                curMode = parent.getItemAtPosition(pos).toString();
            }

            public void onNothingSelected(AdapterView<?> parent) {
                curMode = "";
            }
        });

        settings = getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("client-id", MSG_KEY);
        editor.apply();

        //권한체크
        checkPermission();

        buttonStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (isRecording) {
                    forceStop = true;
                } else {
                    try {
                        new Thread(new Runnable() {
                            public void run() {
                                SendMessage("Recording...", 1);
                                try {
                                    recordSpeech();
                                    SendMessage("Recognizing...", 2);
                                } catch (RuntimeException e) {
                                    SendMessage(e.getMessage(), 3);
                                    return;
                                }

                                Thread threadRecog = new Thread(new Runnable() {
                                    public void run() {
                                        result = sendDataAndGetResult();
                                    }
                                });
                                threadRecog.start();
                                try {
                                    threadRecog.join(20000);
                                    if (threadRecog.isAlive()) {
                                        threadRecog.interrupt();
                                        SendMessage("No response from server for 20 secs", 4);
                                    } else {
                                        SendMessage("OK", 5);
                                    }
                                } catch (InterruptedException e) {
                                    SendMessage("Interrupted", 4);
                                }
                            }
                        }).start();
                    } catch (Throwable t) {
                        textResult.setText("ERROR: " + t.toString());
                        forceStop = false;
                        isRecording = false;
                    }
                }
            }
        });
    }

    public void recordSpeech() throws RuntimeException {
        try {
            int bufferSize = AudioRecord.getMinBufferSize(
                    16000, // sampling frequency
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord audio = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000, // sampling frequency
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            lenSpeech = 0;
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("ERROR: Failed to initialize audio device. Allow app to access microphone");
            } else {
                short[] inBuffer = new short[bufferSize];
                forceStop = false;
                isRecording = true;
                audio.startRecording();
                while (!forceStop) {
                    int ret = audio.read(inBuffer, 0, bufferSize);
                    for (int i = 0; i < ret; i++) {
                        if (lenSpeech >= maxLenSpeech) {
                            forceStop = true;
                            break;
                        }
                        speechData[lenSpeech * 2] = (byte) (inBuffer[i] & 0x00FF);
                        speechData[lenSpeech * 2 + 1] = (byte) ((inBuffer[i] & 0xFF00) >> 8);
                        lenSpeech++;
                    }
                }
                audio.stop();
                audio.release();
                isRecording = false;
            }
        } catch (Throwable t) {
            throw new RuntimeException(t.toString());
        }
    }

    public String sendDataAndGetResult() {
        String openApiURL = "http://aiopen.etri.re.kr:8000/WiseASR/Recognition";
        String accessKey = MSG_KEY;
        String languageCode;
        String audioContents;

        Gson gson = new Gson();

        switch (curMode) {
            case "한국어인식":
                languageCode = "korean";
                break;
            case "영어인식":
                languageCode = "english";
                break;
            case "영어발음평가":
                languageCode = "english";
                openApiURL = "http://aiopen.etri.re.kr:8000/WiseASR/Pronunciation";
                break;
            default:
                return "ERROR: invalid mode";
        }

        Map<String, Object> request = new HashMap<>();
        Map<String, String> argument = new HashMap<>();

        audioContents = Base64.encodeToString(
                speechData, 0, lenSpeech * 2, Base64.NO_WRAP);

        argument.put("language_code", languageCode);
        argument.put("audio", audioContents);

        request.put("access_key", accessKey);
        request.put("argument", argument);

        URL url;
        Integer responseCode;
        final String responBody;
        try {
            url = new URL(openApiURL);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);

            DataOutputStream wr = new DataOutputStream(con.getOutputStream());
            wr.write(gson.toJson(request).getBytes("UTF-8"));
            wr.flush();
            wr.close();

            responseCode = con.getResponseCode();
            if (responseCode == 200) {
                InputStream is = new BufferedInputStream(con.getInputStream());
                responBody = readStream(is);

                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run(){
                        //txt파일로 저장
                        saveFile(responBody);
                    }
                }, 0);

                return responBody;
            } else
                return "ERROR: " + Integer.toString(responseCode);
        } catch (Throwable t) {
            return "ERROR: " + t.toString();
        }
    }

    //권한 승인여부 확인
    public void checkPermission() {
        for (String str : permission_list) {
            if (ContextCompat.checkSelfPermission(this, str) != PackageManager.PERMISSION_GRANTED) {
                //권한이 없을 경우

                //최초 권한 요청인지, 사용자에 의한 재요청인지 확인
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, str)) {
                    //사용자에 의한 재요청 -> 권한 재요청
                    ActivityCompat.requestPermissions(this, new String[]{str}, MY_PERMISSIONS_REQUEST_READ_CONTACTS);
                } else {
                    //최초 권한 요청
                    ActivityCompat.requestPermissions(this, new String[]{str}, MY_PERMISSIONS_REQUEST_READ_CONTACTS);
                }
            } else {
                //사용 권한이 있는 경우
            }
        }
    }

    // 사용자가 권한 허용/거부 버튼을 눌렀을 때 호출되는 메서드
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_DENIED) {
                //사용자가 권한 동의 안함, 권한 동의 취소 버튼 선택
                Toast.makeText(this, "권한허용을 동의한 후, 사용 가능합니다.", Toast.LENGTH_LONG).show();
                finish();
            }
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            //권한 동의 버튼 선택
        }
        return;
    }

    public void saveFile(String saveStr){
        // directory 생성
        File storeDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "VoiceRecognition");
        // 일치하는 directory가 없으면 생성
        if( !storeDir.exists() ) {
            storeDir.mkdirs();
            Log.i(TAG, "directory 생성 성공");
        }

        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd-HH-mm-ss", Locale.KOREA);
        Date date = new Date();
        String fileName = "collathon"+formatter.format(date) + ".txt";

        // txt 파일 생성
        File saveFile = new File(storeDir.getPath() + File.separator + fileName);
        if(saveFile == null){
            Log.i(TAG, "Error at creating .txt file, check storage permissions :");
            return;
        }

        try{
            FileOutputStream fos = new FileOutputStream(saveFile);
            fos.write(saveStr.getBytes());
            fos.close();
            Toast.makeText(this, "txt파일 저장에 성공했습니다.", Toast.LENGTH_SHORT).show();
        } catch(IOException e){
            Toast.makeText(this, "txt파일 저장에 실패했습니다.", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "txt파일 저장에 실패했습니다.");
            e.printStackTrace();
        }
    }
}