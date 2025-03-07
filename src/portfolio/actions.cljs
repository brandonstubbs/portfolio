(ns portfolio.actions
  (:require [clojure.walk :as walk]
            [portfolio.core :as portfolio]
            [portfolio.css :as css]
            [portfolio.router :as router]))

(defn assoc-in*
  "Takes a map and pairs of path value to assoc-in to the map. Makes `assoc-in`
  work like `assoc`, e.g.:

  ```clj
  (assoc-in* {}
             [:person :name] \"Christian\"
             [:person :language] \"Clojure\")
  ;;=>
  {:person {:name \"Christian\"
            :language \"Clojure\"}}
  ```"
  [m & args]
  (assert (= 0 (mod (count args) 2)) "assoc-in* takes a map and pairs of path value")
  (assert (->> args (partition 2) (map first) (every? vector?)) "each path should be a vector")
  (->> (partition 2 args)
       (reduce (fn [m [path v]]
                 (assoc-in m path v)) m)))

(defn dissoc-in*
  "Takes a map and paths to dissoc from it. An example explains it best:

  ```clj
  (dissoc-in* {:person {:name \"Christian\"
                        :language \"Clojure\"}}
              [:person :language])
  ;;=>
  {:person {:name \"Christian\"}}
  ```

  Optionally pass additional paths.
  "
  [m & args]
  (reduce (fn [m path]
            (cond
              (= 0 (count path)) m
              (= 1 (count path)) (dissoc m (first path))
              :else (let [[k & ks] (reverse path)]
                      (update-in m (reverse ks) dissoc k))))
          m args))

(defn atom? [x]
  (satisfies? cljs.core/IWatchable x))

(defn get-page-title [location]
  (let [params (:query-params location)
        scene (when (:scene params) (keyword (:scene params)))]
    (cond
      scene
      (str "Scene: " (name scene) " (" (namespace scene) ") - Portfolio")

      (:namespace params)
      (str "Namespace: " (:namespace params) " - Portfolio"))))

(defn go-to-location [state location]
  (let [current-scenes (portfolio/get-current-scenes state (:location state))
        next-scenes (portfolio/get-current-scenes state location)]
    {:assoc-in [[:location] location]
     :fns (concat
           (->> (filter :on-unmount current-scenes)
                (map (fn [{:keys [on-unmount param id title]}]
                       [:on-unmount (or id title) on-unmount param])))
           (->> (filter :on-mount next-scenes)
                (map (fn [{:keys [on-mount param id title]}]
                       [:on-mount (or id title) on-mount param]))))
     :release (->> (map :param current-scenes)
                   (filter atom?)
                   (map (fn [ref] [ref ::portfolio])))
     :subscribe (->> (map (juxt :param identity) next-scenes)
                     (filter (comp atom? first))
                     (map (fn [[ref scene]] [ref ::portfolio scene])))
     :set-page-title (get-page-title location)
     :update-window-location (router/get-url location)}))

(defn remove-scene-param
  ([state scene-id]
   (let [param (get-in state [:scenes scene-id :param])]
     (cond
       (map? param)
       {:actions [[:dissoc-in [:ui scene-id :overrides]]]}

       (atom? param)
       {:reset [param (get-in state [:ui scene-id :original])]
        :actions [[:dissoc-in [:ui scene-id :overrides]]
                  [:dissoc-in [:ui scene-id :original]]]})))
  ([state scene-id k]
   (let [param (get-in state [:scenes scene-id :param])]
     (cond
       (map? param)
       {:actions [[:dissoc-in [:ui scene-id :overrides k]]]}

       (atom? param)
       {:swap [param [k] (get-in state [:scenes scene-id :original k])]
        :actions [[:dissoc-in [:ui scene-id :overrides k]]
                  [:dissoc-in [:ui scene-id :original k]]]}))))

(defn set-scene-param
  ([state scene-id v]
   (let [param (get-in state [:scenes scene-id :param])]
     (cond
       (map? param)
       {:actions [[:assoc-in [:ui scene-id :overrides] v]]}

       (atom? param)
       {:reset [param v]
        :actions [[:assoc-in [:ui scene-id :overrides] v]
                  [:assoc-in [:ui scene-id :original] @param]]})))
  ([state scene-id k v]
   (let [param (get-in state [:scenes scene-id :param])]
     (cond
       (map? param)
       {:actions [[:assoc-in [:ui scene-id :overrides k] v]]}

       (atom? param)
       {:swap [param [k] v]
        :actions (cond-> [[:assoc-in [:ui scene-id :overrides k] v]]
                   (not (get-in state [:ui scene-id :original k]))
                   (into [[:assoc-in [:ui scene-id :original k] (k @param)]]))}))))

(declare execute-action!)

(defn process-action-result! [app res]
  (doseq [[ref k] (:release res)]
    (println "Stop watching atom" (pr-str ref))
    (remove-watch ref k))
  (doseq [[k t f & args] (:fns res)]
    (println (str "Calling " k " on " t " with") (pr-str args))
    (apply f args))
  (doseq [[ref k scene] (:subscribe res)]
    (println "Start watching atom" (pr-str ref))
    (add-watch ref k (fn [_ _ _ _]
                       (swap! app update :heartbeat (fnil inc 0)))))
  (when-let [url (:update-window-location res)]
    (when-not (= url (router/get-current-url))
      (println "Updating browser URL to" url)
      (.pushState js/history false false url)))
  (when-let [title (:set-page-title res)]
    (set! js/document.title title))
  (when (or (:dissoc-in res) (:assoc-in res))
    (when (:assoc-in res)
      (println ":assoc-in" (pr-str (:assoc-in res))))
    (when (:dissoc-in res)
      (println ":dissoc-in" (pr-str (:dissoc-in res))))
    (swap! app (fn [state]
                 (apply assoc-in*
                        (apply dissoc-in* state (:dissoc-in res))
                        (:assoc-in res)))))
  (doseq [action (:actions res)]
    (execute-action! app action))
  (when-let [[ref path v] (:swap res)]
    (swap! ref assoc-in path v))
  (when-let [[ref v] (:reset res)]
    (reset! ref v))
  (when-let [paths (:load-css-files res)]
    (css/load-css-files paths))
  (when-let [paths (:replace-css-files res)]
    (css/replace-loaded-css-files paths)))

(defn execute-action! [app action]
  (println "execute-action!" action)
  (process-action-result!
   app
   (case (first action)
     :assoc-in {:assoc-in (rest action)}
     :dissoc-in {:dissoc-in (rest action)}
     :fn/call (let [[fn & args] (rest action)] (apply fn args))
     :go-to-location (apply go-to-location @app (rest action))
     :go-to-current-location (go-to-location @app (router/get-current-location))
     :set-css-files (let [[paths] (rest action)]
                      {:assoc-in [[:css-paths] paths]
                       :load-css-files paths
                       :replace-css-files paths})
     :remove-scene-param (apply remove-scene-param @app (rest action))
     :set-scene-param (apply set-scene-param @app (rest action))))
  app)

(def available-actions
  #{:assoc-in :dissoc-in :go-to-location :go-to-current-location
    :remove-scene-param :set-scene-param :fn/call})

(defn actions? [x]
  (and (sequential? x)
       (not (empty? x))
       (every? #(and (sequential? %)
                     (contains? available-actions (first %))) x)))

(defn parse-int [s]
  (let [n (js/parseInt s 10)]
    (if (not= n n)
      ;; NaN!
      0
      n)))

(defn actionize-data
  "Given a Portfolio `app` instance and some prepared data to render, wrap
  collections of actions in a function that executes these actions. Using this
  function makes it possible to prepare event handlers as a sequence of action
  tuples, and have them seemlessly emitted as actions in the components.

  If you need to access the `.-value` of the event target (e.g. for on-change on
  input fields, etc), use `:event.target/value` as a placeholder in your action,
  and it will be replaced with the value."
  [app data]
  (walk/prewalk
   (fn [x]
     (if (actions? x)
       (fn [e]
         (doseq [action x]
           (execute-action!
            app
            (walk/prewalk
             (fn [ax]
               (cond
                 (= :event.target/value ax)
                 (some-> e .-target .-value)

                 (= :event.target/number-value ax)
                 (some-> e .-target .-value parse-int)

                 :default ax))
             action))))
       x))
   data))
