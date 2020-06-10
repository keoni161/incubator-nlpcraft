<!--
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

To install dependencies:  
`$ bin/install_dependencies.sh`  
To start server:  
`$ bin/start_server.sh`  

### Routes
##### /suggestions
Returns word replacement suggestions for following word in the sentence  
* "sentences": List of sentences information. Each sentence encoded as list, first element of which is sentence itself,
  second is position in the sentence of the word to generate suggestions for.  
  Example: ``"sentences": [["foo bar baz", 2], ["sample second sentence", 0]]``
* "simple" (Optional): If set to true omits verbose data.  
* "limit": Sets limit of result words number.  

Simple request with single sentence could be made with a script, e.g.  
`$ bin/predict.sh "what is the chance of rain tomorrow?" 5`  
Would find suggestions for word "rain" in this sentence.