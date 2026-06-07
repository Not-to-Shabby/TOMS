**Chapter 3 Methodology Outline**

**RFID-Based Point-of-Sale and Passenger Monitoring System for Local Public Minibuses**

This methodology outline is based on the Chapter 1 content of the study. The study focuses on the design, development, and evaluation of a portable RFID-based Point-of-Sale (POS) and passenger monitoring system for local public minibuses. The proposed system includes reusable RFID seat cards, GPS-based fare calculation, offline transaction storage, and dashboard synchronization when internet connectivity becomes available.

# **3.1 Research Design**

This section should explain the overall research approach used in the study. Since the study aims to design, develop, and evaluate a working prototype, the recommended approach is a developmental research design or design and development research design.

Discuss that the study will be implemented in three major phases: analysis of the traditional manual fare collection process, design and development of the RFID-based POS prototype, and testing and evaluation of the system performance.

* Explain why a design and development approach is suitable.  
* Identify the major research phases.  
* Connect the research design to the specific objectives of the study.

Suggested Figure: Figure 3.1. Research Methodology Flowchart

**Suggested Table 3.1. Methodology Phases and Expected Outputs**

| Phase | Description | Expected Output |
| :---- | :---- | :---- |
| Analysis | Identify problems in manual fare collection and passenger monitoring. | System requirements |
| Design | Prepare the architecture, hardware design, and software process. | System diagrams and technical specifications |
| Development | Build the RFID-GPS handheld POS prototype and dashboard. | Working prototype |
| Testing | Test RFID scanning, GPS fare calculation, offline storage, and synchronization. | Performance data |
| Evaluation | Compare system outputs with manual records and collect evaluation responses. | Accuracy and effectiveness results |

# **3.2 Locale of the Study**

This section should describe the actual or simulated testing environment. Since the study is intended for public minibuses in Iligan City, the selected minibus route or controlled testing route should be discussed.

* Identify the selected minibus route or simulated route.  
* Explain why the route is suitable for testing.  
* Mention route sections with possible weak or unstable internet connectivity.  
* Describe how the locale supports the study objective.

Suggested Figure: Figure 3.2. Selected Minibus Route or Testing Location Map

**Suggested Table 3.2. Route Testing Profile**

| Route Section | Distance | Signal Condition | Purpose of Testing |
| :---- | :---- | :---- | :---- |
| Section 1 | \_\_\_ km | Stable | Normal transaction test |
| Section 2 | \_\_\_ km | Weak signal | Offline storage test |
| Section 3 | \_\_\_ km | Restored signal | Data synchronization test |

# **3.3 Respondents or Participants of the Study**

This section should identify the people involved in testing and evaluating the system. The main participants may include conductors, operators, commuters, and IT experts.

* Conductors can evaluate usability and actual operation.  
* Operators can evaluate usefulness for revenue and passenger monitoring.  
* Commuters can evaluate convenience and acceptability.  
* IT experts can evaluate technical functionality and reliability.

**Suggested Table 3.3. Respondents of the Study**

| Respondent Group | Role in the Study | Number |
| :---- | :---- | :---- |
| Conductors | Evaluate usability and actual operation | \_\_\_ |
| Operators | Evaluate usefulness for fare and revenue monitoring | \_\_\_ |
| Commuters | Evaluate convenience and acceptability | \_\_\_ |
| IT Experts | Evaluate technical functionality and reliability | \_\_\_ |

# **3.4 System Requirements Analysis**

This section should present the hardware, software, and operational requirements needed to build the prototype. It should directly support the objective of identifying the materials needed for a localized transport monitoring device.

## **3.4.1 Hardware Requirements**

Discuss the purpose of each hardware component used in the system.

**Suggested Table 3.4. Hardware Components and Functions**

| Component | Function |
| :---- | :---- |
| ESP32 Microcontroller | Main controller for RFID scanning, GPS tracking, offline storage, and synchronization |
| RFID Reader | Reads reusable RFID seat cards |
| RFID Cards | Serve as reusable seat markers and digital fare records |
| GPS Module | Tracks vehicle location and supports fare calculation |
| Display Module | Shows transaction status, fare, and passenger information |
| Battery/Power Supply | Provides portable power for handheld operation |
| Enclosure/Case | Protects components and supports handheld use |

## **3.4.2 Software Requirements**

Discuss the programming tools, libraries, database, and dashboard platform used to develop the system.

**Suggested Table 3.5. Software Tools and Purpose**

| Software/Platform | Purpose |
| :---- | :---- |
| Arduino IDE or PlatformIO | Programming the ESP32 microcontroller |
| RFID Library | Reading RFID card data |
| GPS Parsing Library | Processing GPS coordinates and route data |
| Local Storage Module or File System | Saving offline transaction records |
| Database or Cloud Platform | Storing synchronized fare and passenger records |
| Web Dashboard | Displaying transaction logs, passenger manifest, and sync status |

# **3.5 System Architecture**

This is one of the most important sections of the methodology. It should explain how the handheld POS device, RFID reader, GPS module, local storage, internet connection, and dashboard work together.

* Explain the flow from RFID card scanning to fare computation.  
* Describe how GPS data are used.  
* Explain how the device stores data when offline.  
* Describe how data are uploaded when internet connectivity returns.  
* Show how the dashboard receives and displays records.

Suggested Figure: Figure 3.3. System Architecture of the RFID-Based POS and Passenger Monitoring System

Recommended architecture flow: RFID Card → RFID Reader → ESP32 → GPS Module → Fare Calculation → Local Storage → Internet Check → Dashboard Synchronization

# **3.6 Hardware Design and Wiring Diagram**

This section should discuss how the physical prototype is connected. The wiring diagram belongs in the methodology because it explains how the system was constructed before testing.

* Describe the ESP32 pin assignments.  
* Explain RFID reader wiring.  
* Explain GPS module wiring.  
* Discuss display, keypad, button, or indicator wiring if used.  
* Describe the power supply connection and portability consideration.

Suggested Figure: Figure 3.4. Wiring Diagram of the Portable POS Prototype

**Suggested Table 3.6. Pin Configuration of the Prototype**

| Component | ESP32 Pin | Purpose |
| :---- | :---- | :---- |
| RFID SDA | \_\_\_ | RFID communication |
| RFID SCK | \_\_\_ | SPI clock |
| RFID MOSI | \_\_\_ | SPI data |
| RFID MISO | \_\_\_ | SPI data |
| GPS TX/RX | \_\_\_ | Location data |
| Display SDA/SCL | \_\_\_ | Screen output |

# **3.7 Prototype Development**

This section should explain the actual development of the handheld POS prototype. It should be written as a research procedure, not as a step-by-step tutorial.

* Assembly of the ESP32-based handheld POS device.  
* Integration of the RFID reader and reusable RFID cards.  
* Integration of the GPS module for route tracking.  
* Implementation of the device interface.  
* Battery-powered and handheld design consideration.  
* Initial functional testing of the prototype.

Suggested Figures: Figure 3.5. Actual Prototype Assembly; Figure 3.6. Final Handheld POS Prototype; Figure 3.7. POS Device Interface Display

# **3.8 RFID Seat Card Registration and Passenger Monitoring Process**

This section should explain how reusable RFID cards function as seat markers and passenger records. It is important because the study does not require passengers to own personal RFID cards.

* Explain how each RFID card is registered.  
* Describe how a card is assigned to a passenger or seat.  
* Explain how passenger boarding is recorded.  
* Describe how passenger count is updated.  
* Explain how a card is reset or made available after trip completion.

Suggested Figure: Figure 3.8. RFID Seat Card Transaction Flow

Recommended flow: Card Scan → Seat/Card Assignment → Passenger Count Update → Fare Record Creation → Trip Completion → Card Reset/Available Again

**Suggested Table 3.7. RFID Card Data Fields**

| Field | Description |
| :---- | :---- |
| Card ID | Unique RFID card identifier |
| Seat Number | Assigned seat or passenger marker |
| Boarding Location | GPS-based starting point |
| Fare Status | Paid, unpaid, or completed |
| Transaction Time | Date and time of scan |

# **3.9 GPS-Based Fare Calculation Process**

This section should explain how the system calculates fare using GPS data. State whether the system uses distance-based computation, fixed route segments, or a fare matrix.

* Explain how GPS coordinates are captured.  
* Describe how distance or route segment is identified.  
* Explain how the fare is computed.  
* Discuss how the computed fare is validated.  
* Present the fare matrix or route segment basis.

Suggested Figure: Figure 3.9. GPS-Based Fare Calculation Flowchart

Recommended flow: GPS Start Point → Current GPS Point → Distance or Route Section Identification → Fare Matrix Check → Fare Computation → Transaction Record

**Suggested Table 3.8. Sample Fare Matrix**

| Route Segment | Distance Range | Fare |
| :---- | :---- | :---- |
| Segment 1 | 0–4 km | ₱\_\_\_ |
| Segment 2 | 4.1–8 km | ₱\_\_\_ |
| Segment 3 | 8.1 km and above | ₱\_\_\_ |

# **3.10 Offline-First Data Storage and Synchronization**

This section is central to the study because the system is designed to continue processing transactions even when internet connectivity is weak or unavailable.

* Explain how the device checks internet availability.  
* Describe what happens when the device is offline.  
* Identify the transaction data saved locally.  
* Explain how pending records are marked.  
* Describe how records are uploaded when internet returns.  
* Explain how duplicate synchronization is prevented.

Suggested Figure: Figure 3.10. Offline-First Data Synchronization Process

Recommended flow: Transaction Created → Check Internet → If Online: Upload to Dashboard → If Offline: Save Locally → Recheck Internet → Sync Pending Records → Mark as Synced

**Suggested Table 3.9. Transaction Record Structure**

| Data Field | Description |
| :---- | :---- |
| Transaction ID | Unique record number |
| RFID Card ID | Card used by passenger |
| GPS Location | Location of transaction |
| Fare Amount | Computed fare |
| Timestamp | Date and time |
| Sync Status | Synced or pending |

# **3.11 Web-Based Dashboard Development**

This section should explain the dashboard that receives and displays synchronized data. It should focus on what transport operators or administrators can see after the device uploads records.

* Passenger manifest display.  
* Fare transaction records.  
* Seat occupancy status.  
* Route or GPS logs.  
* Synchronization status.  
* Operator or administrator view.

Suggested Figures: Figure 3.11. Dashboard Login or Main Interface; Figure 3.12. Passenger Manifest Dashboard; Figure 3.13. Fare Transaction Records Dashboard

**Suggested Table 3.10. Dashboard Features**

| Feature | Description |
| :---- | :---- |
| Passenger Manifest | Shows current passenger records |
| Fare Records | Displays collected fare transactions |
| Seat Occupancy | Shows occupied and available seats |
| Sync Status | Identifies synced and pending records |
| Route Logs | Displays GPS-related trip information |

# **3.12 System Operation Procedure**

This section should describe the complete use-case flow from conductor operation to dashboard update.

1. The conductor turns on the handheld POS device.  
2. The passenger boards the minibus.  
3. The conductor scans a reusable RFID seat card.  
4. The GPS location is captured.  
5. The fare is calculated.  
6. The passenger count is updated.  
7. The transaction is saved.  
8. If online, data are synchronized to the dashboard.  
9. If offline, data remain in local storage until connection returns.

Suggested Figure: Figure 3.14. Complete System Operation Flowchart

# **3.13 Testing Procedure**

This section should explain how the system will be tested. Since the scope allows controlled simulations and short-term field trials, both should be discussed separately.

## **3.13.1 Controlled Simulation Testing**

* RFID card reading test.  
* Fare calculation test.  
* Passenger count update test.  
* Offline transaction saving test.  
* Dashboard synchronization test.  
* Duplicate transaction prevention test.

## **3.13.2 Short-Term Field Trial**

* GPS tracking during movement.  
* Transaction processing while mobile.  
* Offline storage during weak signal.  
* Synchronization after internet recovery.  
* Usability by conductor or operator.

**Suggested Table 3.11. Testing Scenarios**

| Scenario | Purpose | Expected Output |
| :---- | :---- | :---- |
| RFID scan test | Check card reading | Card ID detected |
| Passenger count test | Check occupancy update | Count increases or decreases correctly |
| GPS fare test | Check fare calculation | Correct fare generated |
| Offline test | Check local storage | Transaction saved as pending sync |
| Sync test | Check dashboard upload | Records uploaded successfully |

# **3.14 Evaluation Metrics**

This section should explain how the performance of the system will be measured. The metrics should directly assess accuracy and effectiveness in generating passenger records and fare transactions.

**Passenger Count Accuracy:** Correct Passenger Count Records / Total Test Transactions × 100

**Fare Calculation Accuracy:** Correct Fare Computations / Total Fare Computation Tests × 100

**RFID Reading Success Rate:** Successful RFID Scans / Total RFID Scan Attempts × 100

**Offline Storage Success Rate:** Successfully Saved Offline Records / Total Offline Transactions × 100

**Synchronization Success Rate:** Successfully Uploaded Records / Total Pending Records × 100

**Average Transaction Processing Time:** Average time from RFID scan to fare record completion

**Suggested Table 3.12. System Evaluation Metrics**

| Metric | Purpose | Formula/Measurement |
| :---- | :---- | :---- |
| Passenger Count Accuracy | Measures passenger monitoring correctness | Correct records / total records × 100 |
| Fare Calculation Accuracy | Measures correctness of computed fare | Correct fares / total tests × 100 |
| RFID Success Rate | Measures RFID reading reliability | Successful scans / total scans × 100 |
| Offline Storage Success Rate | Measures offline transaction reliability | Saved records / offline records × 100 |
| Sync Success Rate | Measures upload reliability | Uploaded records / pending records × 100 |
| Processing Time | Measures transaction speed | Average seconds per transaction |

# **3.15 Comparison with the Traditional Manual System**

Since the problem statement focuses on manual cash collection, paper ticketing, and manual passenger counting, this section should explain how the developed system will be compared with the traditional method.

* Passenger count accuracy.  
* Fare record accuracy.  
* Transaction speed.  
* Paper ticket reduction.  
* Revenue recording reliability.

**Suggested Table 3.13. Manual System vs. RFID-Based POS Evaluation Criteria**

| Criteria | Manual System | RFID-Based POS System |
| :---- | :---- | :---- |
| Passenger counting | Manually counted | Automatically recorded |
| Fare recording | Cash or ticket-based | Digitally recorded |
| Offline operation | Not applicable | Locally stored |
| Data availability | Manual summary | Dashboard-based |
| Paper use | Uses paper tickets | Uses reusable RFID cards |

# **3.16 Survey or Expert Evaluation Procedure**

Use this section if the study includes user or expert evaluation. The evaluation may use a 5-point Likert scale to measure functionality, usability, reliability, efficiency, acceptability, and practicality.

**Suggested Table 3.14. Evaluation Scale**

| Scale | Range | Interpretation |
| :---- | :---- | :---- |
| 5 | 4.21–5.00 | Very Acceptable |
| 4 | 3.41–4.20 | Acceptable |
| 3 | 2.61–3.40 | Moderately Acceptable |
| 2 | 1.81–2.60 | Less Acceptable |
| 1 | 1.00–1.80 | Not Acceptable |

**Suggested Table 3.15. Evaluation Criteria**

| Criteria | Description |
| :---- | :---- |
| Functionality | Ability of the system to perform required tasks |
| Usability | Ease of use for conductors and operators |
| Reliability | Consistency of RFID, GPS, storage, and synchronization |
| Efficiency | Speed of transaction processing |
| Acceptability | Overall usefulness and willingness to use |
| Practicality | Suitability for localized minibus operations |

# **3.17 Data Gathering Procedure**

This section should explain how data will be collected during prototype testing and evaluation.

* RFID scan logs.  
* GPS coordinates.  
* Fare computation records.  
* Passenger count records.  
* Offline transaction logs.  
* Synchronization logs.  
* Survey responses.  
* Observation notes.

Suggested Figure: Figure 3.15. Data Gathering Procedure

Recommended flow: Prototype Testing → Transaction Logs → Dashboard Records → Manual Comparison → Survey Evaluation → Data Analysis

# **3.18 Data Analysis Procedure**

This section should explain how the collected data will be analyzed. The analysis should be simple, measurable, and aligned with the system objectives.

* Use frequency and percentage for system success rates.  
* Use mean and interpretation for survey results.  
* Use accuracy rate for passenger count and fare computation.  
* Use average time for transaction processing.  
* Compare manual and automated records.

**Suggested Table 3.16. Data Analysis Plan**

| Data Collected | Analysis Method | Output |
| :---- | :---- | :---- |
| RFID scan results | Percentage | RFID success rate |
| Fare calculation records | Accuracy computation | Fare accuracy |
| Passenger count logs | Accuracy computation | Passenger count accuracy |
| Offline records | Percentage | Offline storage success rate |
| Sync records | Percentage | Sync success rate |
| Survey responses | Weighted mean | Acceptability rating |

# **3.19 Ethical Considerations**

Even if the study is technical, this section should still be included. It should explain how the researchers will protect participants and data during system testing.

* Secure consent from conductors, operators, commuters, and experts involved in the evaluation.  
* Avoid collecting unnecessary personal information.  
* Keep transaction and evaluation records confidential.  
* Use the dashboard data only for academic and research purposes.  
* Request permission from transport operators or relevant offices before field testing.

# **3.20 Summary of Methodology**

This section should briefly summarize the entire methodology chapter. It should mention the research design, system requirements, prototype development process, RFID and GPS integration, offline-first synchronization, dashboard development, testing procedure, and evaluation methods.

Suggested paragraph: This chapter presented the methodology for the design, development, and evaluation of the RFID-based POS and passenger monitoring system. The procedures covered the analysis of system requirements, development of the handheld prototype, RFID seat card processing, GPS-based fare calculation, offline data storage, dashboard synchronization, and system evaluation. These methods were designed to determine whether the proposed system can accurately record fares, monitor passenger occupancy, and operate reliably during unstable internet connectivity.

# **Recommended Final Chapter 3 Outline**

10. 3.1 Research Design

Phase I:

11. 3.2 Locale of the Study

3.2.1  
3.2.2

12. 3.3 Respondents or Participants of the Study  
13. 3.4 System Requirements Analysis

Phase II:

14. 3.5 System Architecture  
15. 3.6 Hardware Design and Wiring Diagram

Phase III:

16. 3.7 Prototype Development  
17. 3.8 RFID Seat Card Registration and Passenger Monitoring Process  
18. 3.9 GPS-Based Fare Calculation Process  
19. 3.10 Offline-First Data Storage and Synchronization  
20. 3.11 Web-Based Dashboard Development

Phase IV:

21. 3.12 System Operation Procedure  
22. 3.13 Testing Procedure  
23. 3.14 Evaluation Metrics

Phase V:

24. 3.15 Comparison with the Traditional Manual System  
25. 3.16 Survey or Expert Evaluation Procedure  
26. 3.17 Data Gathering Procedure  
27. 3.18 Data Analysis Procedure  
28. 3.19 Ethical Considerations  
29. 3.20 Summary of Methodology