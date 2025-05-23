(ns gemini-server.core
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.json :as json]
            [wkok.openai-clojure.api :as openai]
            [clj-http.client :as client]
            [clojure.string :as str]
	    [cheshire.core :as jsn]))

(def gemini-user-id "Gemini")
(def gemini-api-key (System/getenv "GEMINI_API_KEY"))

(defn messages-to-gemini-contents [messages]
  (let [system-message (first (filter #(= (:role %) "system") messages))
        user-messages (filter #(not= (:role %) "system") messages)]
    (cond-> {:contents (mapv (fn [{:keys [role content]}]
                               {:role (if (= role "assistant") "model" role)
                                :parts [{:text content}]})
                             user-messages)}
      system-message (assoc :system_instruction
                            {:parts [{:text (:content system-message)}]}))))


;; 调用 Gemini API
(defn call-gemini [api-key model-name messages]
  (let [api-url (str "https://generativelanguage.googleapis.com/v1beta/models/" model-name ":generateContent")
        headers {"Content-Type" "application/json"}
        body (messages-to-gemini-contents messages)
        query-params {:key api-key}]
    ;;(println "========== MESSAGES BEING SENT TO GEMINI START ==========")
    ;;(clojure.pprint/pprint body)
    ;;(println "========== MESSAGES BEING SENT TO GEMINI END ==========")
    (try
      (let [response (client/post api-url {:headers headers
                                          :query-params query-params
                                          :body (jsn/generate-string body)
                                          :throw-exceptions false
                                          :as :json-string-keys})]
        ;;(println "========== GEMINI API RESPONSE START ==========")
        ;;(clojure.pprint/pprint response)
        ;;(println "========== GEMINI API RESPONSE END ==========")
        (if (= 200 (:status response))
          (:body response) ; Return raw body
          (do
            (println (str "Error: API request failed with status " (:status response)))
            {:error true
             :status (:status response)
             :body (:body response)})))
      (catch Exception e
        (println (str "Gemini API request exception: " (.getMessage e)))
        {:error true
         :message (str "Request exception: " (.getMessage e))}))))

;; 调用 Gemini API 包装
(defn call-gemini-api [msg]
  (println (str "Calling Gemini API with message: " msg))
  (d/future
    (try
      (let [messages [{:role "user" :content msg}]
            api-key (System/getenv "GEMINI_API_KEY")
            model-name "gemini-2.0-flash"
            response (call-gemini api-key model-name messages)]
        ;;(println "response from call-gemini-api is:")
        ;;(clojure.pprint/pprint response)
        (if (:error response)
          (do
            (println (str "Error: API call failed with status " (:status response)))
            (str "Error: " (:message response)))
          (let [content (or (get-in response ["candidates" 0 "content" "parts" 0 "text"])
                            (do
                              (println "Error: Invalid response structure, falling back to raw response")
                              (str "Response: " (pr-str response))))]
            ;;(println (str "Gemini API response: " content))
            content)))
      (catch Exception e
        (println (str "Gemini API exception: " (.getMessage e)))
        (str "Error: " (.getMessage e))))))

(defn start-gemini-client []
  (println "Starting Gemini client, connecting to ws://vajra.one:8080?room=default")
  (let [ws-conn @(http/websocket-client "ws://vajra.one:8080?room=default")]
    (println (str "Gemini client connected: " gemini-user-id))
    ;; 发送加入通知
    (let [join-msg (json/write-str
                     {:user-id "System"
                      :message (str gemini-user-id " joined")
                      :type "system"
                      :timestamp (str (java.time.Instant/now))
                      :room-id "default"})]
      (println (str "Sending join message: " join-msg))
      (s/put! ws-conn join-msg))
    ;; 处理消息
(s/consume
  (fn [msg]
    (try
      (if (and (string? msg) (not-empty msg))
        (let [parsed (json/read-str msg :key-fn keyword)]
          (println (str "Received message: " msg))
          (when (and (not= (:user-id parsed) gemini-user-id)
                     (or (str/includes? (:message parsed) "@Gemini")
                         (str/includes? (:message parsed) "@all")))
            (let [think-msg (json/write-str
                              {:user-id "System"
                               :message "Gemini is thinking..."
                               :type "system"
                               :timestamp (str (java.time.Instant/now))
                               :room-id "default"})]
              (println (str "Sending thinking message: " think-msg))
              (when-not (s/closed? ws-conn)
                (s/put! ws-conn think-msg)))
            (-> (call-gemini-api (str/replace (:message parsed) #"@Gemini|@all" ""))
                (d/chain
                  (fn [response]
                    (let [response-msg (json/write-str
                                         {:user-id gemini-user-id
                                          :message response
                                          :type "llm"
                                          :timestamp (str (java.time.Instant/now))
                                          :room-id "default"})]
                      (println (str "Sending Gemini response: " response-msg))
                      (when-not (s/closed? ws-conn)
                        (s/put! ws-conn response-msg)))))
                (d/catch
                  (fn [error]
                    (println (str "Error in API response handling: " error))
                    (when-not (s/closed? ws-conn)
                      (s/put! ws-conn
                              (json/write-str
                                {:user-id "System"
                                 :message (str "Gemini failed to respond: " error)
                                 :type "system"
                                 :timestamp (str (java.time.Instant/now))
                                 :room-id "default"}))))))))
        (println (str "Invalid message received: " (pr-str msg))))
      (catch Exception e
        (println (str "Error processing message: " (.getMessage e))))))
  ws-conn)
    ;; 清理连接
    (s/on-closed ws-conn
      (fn []
        (println (str "Gemini client disconnected: " gemini-user-id))
        (let [leave-msg (json/write-str
                          {:user-id "System"
                           :message (str gemini-user-id " left")
                           :type "system"
                           :timestamp (str (java.time.Instant/now))
                           :room-id "default"})]
          (println (str "Sending leave message: " leave-msg))
          (s/put! ws-conn leave-msg))))
    ws-conn))

(defn -main []
  (println "Starting Gemini server")
  (start-gemini-client)
  (println "Gemini server running")
  (Thread/sleep Long/MAX_VALUE))
