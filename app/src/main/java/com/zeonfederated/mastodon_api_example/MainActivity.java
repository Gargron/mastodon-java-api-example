package com.zeonfederated.mastodon_api_example;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    SharedPreferences pref;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pref = getSharedPreferences("MastodonApiExample", MODE_PRIVATE);

        Button loginBtn = (Button) findViewById(R.id.button);
        loginBtn.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                EditText instanceDomainInput = (EditText) findViewById(R.id.editText);
                String instanceDomain = instanceDomainInput.getText().toString();
                new LoginTask().execute(instanceDomain);
            }

        });
    }

    private String getQuery(List<Pair<String, String>> params) throws UnsupportedEncodingException {
        StringBuilder result = new StringBuilder();
        boolean first = true;

        for (Pair<String, String> pair : params)
        {
            if (first)
                first = false;
            else
                result.append("&");

            result.append(URLEncoder.encode(pair.first, "UTF-8"));
            result.append("=");
            result.append(URLEncoder.encode(pair.second, "UTF-8"));
        }

        return result.toString();
    }

    class LoginTask extends AsyncTask<String, Void, OAuth20Service> {
        Dialog authDialog;

        @Override
        protected OAuth20Service doInBackground(String... urls) {
            String instanceDomain = urls[0];

            String clientId = pref.getString(String.format("client_id_for_%s", instanceDomain), null);
            String clientSecret = pref.getString(String.format("client_secret_for_%s", instanceDomain), null);

            Log.d("LoginTask", "client id saved: " + clientId);
            Log.d("LoginTask", "client secret saved: " + clientSecret);

            if(clientId == null || clientSecret == null) {
                // Client registration
                Log.d("LoginTask", "Going to fetch new client id/secret");

                try {
                    URL url = new URL(String.format("https://%s/api/v1/apps", instanceDomain));
                    HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

                    urlConnection.setRequestMethod("POST");

                    List<Pair<String, String>> params = new ArrayList<Pair<String, String>>();
                    params.add(new Pair<String, String>("client_name", "Mastodon API Example"));
                    params.add(new Pair<String, String>("redirect_uris", "http://localhost"));

                    OutputStream os = urlConnection.getOutputStream();
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
                    writer.write(getQuery(params));
                    writer.flush();
                    writer.close();
                    os.close();

                    urlConnection.connect();

                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;

                        while((line = br.readLine()) != null) {
                            sb.append(line + "\n");
                        }

                        br.close();
                        JSONObject json = new JSONObject(sb.toString());
                        clientId = json.getString("client_id");
                        clientSecret = json.getString("client_secret");

                        SharedPreferences.Editor edit = pref.edit();
                        edit.putString(String.format("client_id_for_%s", instanceDomain), clientId);
                        edit.putString(String.format("client_secret_for_%s", instanceDomain), clientSecret);
                        edit.commit();
                    } catch (JSONException e) {
                        e.printStackTrace();
                    } finally {
                        urlConnection.disconnect();
                    }
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return new ServiceBuilder()
                    .apiKey(clientId)
                    .apiSecret(clientSecret)
                    .callback("http://localhost")
                    .build(MastodonApi.instance(instanceDomain));
        }

        @Override
        protected void onPostExecute(final OAuth20Service service) {
            // OAuth2 flow
            String authUrl = service.getAuthorizationUrl();

            authDialog = new Dialog(MainActivity.this);
            authDialog.setContentView(R.layout.auth_dialog);

            WebView web = (WebView) authDialog.findViewById(R.id.webView);
            web.getSettings().setJavaScriptEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);

                    Log.d("LoginTask", "URL loaded: " + url);

                    if(url.contains("?code=")) {
                        Uri uri = Uri.parse(url);
                        String authCode = uri.getQueryParameter("code");
                        Log.d("LoginTask", "Auth code is: " + authCode);
                        new GetAccessTokenTask().execute(service, authCode);
                        authDialog.dismiss();
                    }
                }
            });

            web.loadUrl(authUrl);

            authDialog.show();
            authDialog.setTitle("Authorize");
            authDialog.setCancelable(true);
        }
    }

    class GetAccessTokenTask extends AsyncTask<Object, Void, OAuth20Service> {

        @Override
        protected OAuth20Service doInBackground(Object... params) {
            OAuth20Service service = (OAuth20Service) params[0];
            String authCode = (String) params[1];
            String domain = Uri.parse(service.getAuthorizationUrl()).getHost();

            try {
                final OAuth2AccessToken token = service.getAccessToken(authCode);

                SharedPreferences.Editor edit = pref.edit();
                edit.putString(String.format("access_token_for_%s", domain), token.getAccessToken());
                edit.commit();

                Log.d("GetAccessTokenTask", "Access token for " + domain + ": " + token.getAccessToken());
            } catch (IOException e) {
                e.printStackTrace();
            }

            return service;
        }

        @Override
        protected void onPostExecute(OAuth20Service service) {
            new LoadProfileTask().execute(service);
        }
    }

    class LoadProfileTask extends AsyncTask<OAuth20Service, Void, String> {

        @Override
        protected String doInBackground(OAuth20Service... params) {
            OAuth20Service service = params[0];
            String domain = Uri.parse(service.getAuthorizationUrl()).getHost();
            OAuth2AccessToken token = new OAuth2AccessToken(pref.getString(String.format("access_token_for_%s", domain), null));

            final OAuthRequest request = new OAuthRequest(Verb.GET, String.format("https://%s/api/v1/accounts/verify_credentials", domain), service);
            service.signRequest(token, request);
            final Response response = request.send();

            try {
                return response.getBody();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        protected void onPostExecute(String response) {
            TextView debug = (TextView) findViewById(R.id.textView);
            debug.setText(response);
        }
    }
}
