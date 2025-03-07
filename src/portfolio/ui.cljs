(ns portfolio.ui
  (:require [portfolio.actions :as actions]
            [portfolio.client :as client]
            [portfolio.core :as portfolio]
            [portfolio.data :as data]
            [portfolio.homeless :as h]
            [portfolio.views.canvas :as canvas]
            [portfolio.views.canvas.background :as canvas-bg]
            [portfolio.views.canvas.grid :as canvas-grid]
            [portfolio.views.canvas.split :as split]
            [portfolio.views.canvas.viewport :as canvas-vp]
            [portfolio.views.canvas.zoom :as canvas-zoom]))

(def app (atom nil))

(defn create-app [config canvas-tools extra-canvas-tools]
  (-> (assoc config
             :scenes (vals @data/scenes)
             :namespaces (vals @data/namespaces)
             :collections (vals @data/collections))
      portfolio/init-state
      (assoc :views [(canvas/create-canvas
                      {:canvas/layout (:canvas/layout config)
                       :tools (into (or canvas-tools
                                        [(canvas-bg/create-background-tool config)
                                         (canvas-vp/create-viewport-tool config)
                                         (canvas-grid/create-grid-tool config)
                                         (canvas-zoom/create-zoom-tool config)
                                         (split/create-split-horizontally-tool config)
                                         (split/create-split-vertically-tool config)
                                         (split/create-close-tool config)])
                                    extra-canvas-tools)})])))

(def eventually-execute (h/debounce actions/execute-action! 250))

(defn start! [& [{:keys [on-render config canvas-tools extra-canvas-tools]}]]
  (swap! app merge (create-app config canvas-tools extra-canvas-tools))
  (add-watch data/scenes ::app (fn [_ _ _ scenes]
                                 (swap! app assoc :scenes scenes)
                                 (eventually-execute app [:go-to-current-location])))
  (add-watch data/namespaces ::app (fn [_ _ _ namespaces] (swap! app assoc :namespaces namespaces)))
  (add-watch data/collections ::app (fn [_ _ _ collections] (swap! app assoc :collections collections)))
  (client/start-app app {:on-render on-render}))
