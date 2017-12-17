package org.tensorflow.demo

import org.tensorflow.demo.Classifier.Recognition

interface ResultsView {
    fun setResults(results: List<Recognition>)
    fun getResults(results: List<Recognition>): Float
}
