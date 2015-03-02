(ns embang.smc
  (:refer-clojure :exclude [rand rand-int rand-nth])
  (:use embang.state
        embang.inference
        [embang.runtime :only [observe]]))

;;; SMC

(derive ::algorithm :embang.inference/algorithm)

(defmethod checkpoint [::algorithm embang.trap.observe] [_ obs]
  ;; update the weight and return the observation checkpoint
  ;; for possible resampling
  (update-in obs [:state]
             add-log-weight (observe (:dist obs) (:value obs))))

(declare resample)

(defmulti sweep
  "a single sweep of SMC and friends"
  (fn [algorithm prog value number-of-particles & _] algorithm))

(defmethod sweep ::algorithm
  [algorithm prog value number-of-particles]
  (loop [particles (repeatedly number-of-particles
                               #(exec algorithm
                                      prog value initial-state))]
    (cond
     (every? #(instance? embang.trap.observe %) particles)
     (recur (map #(exec algorithm (:cont %) nil (:state %))
                 (resample particles number-of-particles
                           :outgoing-weight :mean)))

     (every? #(instance? embang.trap.result %) particles)
     particles

     :else (throw (AssertionError.
                   "some `observe' directives are not global")))))

(defmethod infer :smc [_ prog value & {:keys [number-of-particles]
                                       :or {number-of-particles 1}}]
  (assert (>= number-of-particles 1)
          ":number-of-particles must be at least 1")
  (letfn [(sample-seq []
            (lazy-seq
              (let [particles (sweep ::algorithm
                                     prog value number-of-particles)]
                (concat (map :state particles) (sample-seq)))))]
    (sample-seq)))

;;; Resampling particles

;; Systematic resampling is used. The particles are assigned
;; unit weight after resampling.

(defn recover-weights
  "recovers weights from log-weights"
  [log-weights]
  (let [max-log-weight (reduce max log-weights)]
    (map (fn [log-weight]
           ;; avoid NaN and Infinity
           (cond
            (= log-weight max-log-weight) 1.
            (= log-weight (Math/log 0.)) 0.
            :else (Math/exp (- log-weight max-log-weight))))
         log-weights)))

(defn resample
  "resamples particles proportionally to their current weights;
  returns a sequence of number-of-new-particles resampled
  particles with the same weight (:outgoing-weight), either the
  mean weight (:mean) or, by default, 1 (:one)"
  [particles number-of-new-particles & {:keys [outgoing-weight]
                                        :or {outgoing-weight :one}}]
  ;; Invariant bindings for sampling
  (let [weights (recover-weights
                  (map (comp get-log-weight :state) particles))
        total-weight (reduce + weights)
        step (/ total-weight number-of-new-particles)
        ;; After resampling, all particles have the same weight.
        log-weight (case outgoing-weight
                     :one 0.
                     :mean (Math/log step))
        ;; The particle sequence is circular.
        all-weights weights
        all-particles particles]

    ;; The systematic sampling loop
    (loop [x (rand total-weight)
           n 0      ; number of particles sampled so far
           acc 0    ; upper bound of the current segment
           weights all-weights
           particles all-particles
           new-particles nil]
      (if (= n number-of-new-particles)
        new-particles
        (let [[weight & next-weights] weights
              [particle & next-particles] particles
              next-acc (+ acc weight)]

          (if (< x next-acc)
            ;; Found the wheel segment into which x has fallen.
            ;; Advance x by step for the next particle's segment.
            (recur (+ x step) (+ n 1)
                   acc weights particles
                   (conj new-particles
                         (update-in
                           particle [:state]
                           set-log-weight log-weight)))
            ;; Otherwise, keep going through the particle's
            ;; segments, recycling the list of particles and
            ;; their weights when necessary.
            (recur x n
                   next-acc
                   (or next-weights all-weights)
                   (or next-particles all-particles)
                   new-particles)))))))
