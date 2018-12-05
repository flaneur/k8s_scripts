#!/usr/bin/env groovy

node() {

	// Variables from Jenkins
	try { REPOSITORY = "${REPOSITORY}" }
	catch(e) { error("Parameter REPOSITORY not defined.") }

	try { PROJECT = "${PROJECT}" }
	catch(e) { error("Parameter PROJECT not defined.") }

	try { NAMESPACE = "${NAMESPACE}" }
	catch(e) { error("Parameter NAMESPACE not defined.") }

	try { GIT_COMMIT = "${GIT_COMMIT}" }
	catch(e) { error("Parameter GIT_COMMIT not defined.") }

	try { ENVIRONMENT = "${ENVIRONMENT}" }
	catch(e) { ENVIRONEMNT = "aws-dev" }

	try { SERVICE_NAME = "${SERVICE_NAME}" }
	catch(e) { SERVICE_NAME = "${REPOSITORY}" }

	SERVICE_NAME_O = "${SERVICE_NAME}"

	try {
		// Is a pull request ? then change the service name
		PULL_REQUEST = "${PULL_REQUEST}"
		if (PULL_REQUEST?.trim()) {
			SERVICE_NAME = "${SERVICE_NAME}-pr${PULL_REQUEST}"
		}
	} catch(e) { println "Pull request not available." }

	// Set build name for Jenkins
	currentBuild.displayName = "#${BUILD_NUMBER} - ${SERVICE_NAME} - ${GIT_COMMIT}"

	// Namespaces list
	def namespaceList = NAMESPACE.split(',')

	// WORKSPACE path, example: /var/lib/jenkins/jobs/XXXXXXX-service/workspace
	def projectRoot = pwd()

	// Credentials to get access to Bitbucket
	// IMPROVE! Check how to get credentials from Jenkins
	def gitCredentials = "bd133f30-9f0e-4ea7-a018-1e0a646ec03c"

	// Checkout Service
	def serviceRepository = "ssh://git@bitbucket/${PROJECT}/${REPOSITORY}.git"
	def serviceBranch = "${GIT_COMMIT}"
	def servicePath = "${projectRoot}/SERVICE_PATH"
	checkout([$class: 'GitSCM', branches: [[name: serviceBranch]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CleanBeforeCheckout'],[$class: 'RelativeTargetDirectory', relativeTargetDir: "SERVICE_PATH"]], submoduleCfg: [], userRemoteConfigs: [[ credentialsId: gitCredentials, url: serviceRepository ]]])

	// Checkout platform-ansible
	def platformRepository = "ssh://git@bitbucket/spark/platform-ansible.git"
	def platformBranch = "*/master"
	def platformPath = "${projectRoot}/PLATFORM_PATH"
	checkout([$class: 'GitSCM', branches: [[name: platformBranch]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CleanBeforeCheckout'],[$class: 'RelativeTargetDirectory', relativeTargetDir: "PLATFORM_PATH"]], submoduleCfg: [], userRemoteConfigs: [[ credentialsId: gitCredentials, url: platformRepository ]]])

	// K8s Deployment file
	def k8sDeploymentFile = "${projectRoot}/SERVICE_PATH/${SERVICE_NAME_O}/src/main/deploy/k8s.deployment.yml"
	if (!fileExists("${k8sDeploymentFile}")) {

		k8sDeploymentFile = "${projectRoot}/SERVICE_PATH/src/main/deploy/k8s.deployment.yml"
		if (!fileExists("${k8sDeploymentFile}")) {

			k8sDeploymentFile = "${projectRoot}/SERVICE_PATH/deploy/k8s.deployment.yml"
			if (!fileExists("${k8sDeploymentFile}")) {
				k8sDeploymentFile = "${projectRoot}/SERVICE_PATH/${REPOSITORY}/src/main/deploy/k8s.deployment.yml"
			}
		}
	}

	// K8s Ingress and Service file
	def k8sIngressFile = "${projectRoot}/SERVICE_PATH/${SERVICE_NAME_O}/src/main/deploy/k8s.ingress.yml"
	if (!fileExists("${k8sIngressFile}")) {

		k8sIngressFile = "${projectRoot}/SERVICE_PATH/deploy/k8s.ingress.yml"
		if (!fileExists("${k8sIngressFile}")) {
			k8sIngressFile = "${projectRoot}/PLATFORM_PATH/jenkins/k8s.ingress.yml"
		}
	}

	// K8s config file
    def k8sConfigFile = "${projectRoot}/PLATFORM_PATH/jenkins/k8s-aws-dev.conf"
	if (ENVIRONMENT=="aws-dev") {
		k8sConfigFile = "${projectRoot}/PLATFORM_PATH/jenkins/k8s-aws.conf"
	} else if (ENVIRONMENT=="aws") {
		k8sConfigFile = "${projectRoot}/PLATFORM_PATH/jenkins/k8s-bare.conf"
	} else if (ENVIRONMENT=="bare") {
		error("You can't deploy to BARE.")
	}

	def kubectl = "kubectl --kubeconfig=${k8sConfigFile}"

	def k8sAmountOfReplicas = 1

	// get containerPort from deployment to set targetPort from service
	sh "grep -m 1 containerPort ${k8sDeploymentFile} | cut -d ':' -f 2 > targetPort"
	def targetPort = readFile("targetPort").trim()

	// This variable is to check if some stage of pipeline fail
	def pipelineError = [:]

	for (int i=0; i < namespaceList.length; i++) {
		def namespace = namespaceList[i].trim()

		pipelineError[namespace] = false

		// Each namespace has a different Deployment and Ingress/Service file
		k8sDeploymentFileTMP = "${k8sDeploymentFile}.${namespace}"
		k8sIngressFileTMP = "${k8sIngressFile}.${namespace}"

		sh "cp ${k8sDeploymentFile} ${k8sDeploymentFileTMP}"
		sh "cp ${k8sIngressFile} ${k8sIngressFileTMP}"

		// Change variables in Deployment file
		sh "sed -i 's/_ACTIVE_/master-${namespace}/g' ${k8sDeploymentFileTMP}"
		sh "sed -i 's/_SERVICE_NAME_/${SERVICE_NAME}/g' ${k8sDeploymentFileTMP}"
		sh "sed -i 's/_REPOSITORY_/${REPOSITORY}/g' ${k8sDeploymentFileTMP}"
		sh "sed -i 's/_TAG_/${GIT_COMMIT}/g' ${k8sDeploymentFileTMP}"
		sh "sed -i 's/_AMOUNT_OF_REPLICAS_/${k8sAmountOfReplicas}/g' ${k8sDeploymentFileTMP}"
		sh "sed -i 's/_NAMESPACE_/${namespace}/g' ${k8sDeploymentFileTMP}"
        if (params.NODE_SELECTOR_LABEL) {
            sh "sed -i 's#_NODE_SELECTOR_LABEL_#${params.NODE_SELECTOR_LABEL}#g' ${k8sDeploymentFileTMP}"
        }
        if (params.NODE_SELECTOR_VALUE) {
            sh "sed -i 's#_NODE_SELECTOR_VALUE_#${params.NODE_SELECTOR_VALUE}#g' ${k8sDeploymentFileTMP}"
        }

		// Change variables in Ingress file
		sh "sed -i 's/_NAMESPACE_/${namespace}/g' ${k8sIngressFileTMP}"
		sh "sed -i 's/_SERVICE_NAME_/${SERVICE_NAME}/g' ${k8sIngressFileTMP}"
		sh "sed -i 's/_TARGET_PORT_/${targetPort}/g' ${k8sIngressFileTMP}"
        if (ENVIRONMENT=="aws") {
            sh "sed -i 's/dev.io/prod.io/g' ${k8sIngressFileTMP}"
        }

		// DEBUG: Show Deployment and Ingress file
		sh "cat ${k8sIngressFileTMP}"
		sh "cat ${k8sDeploymentFileTMP}"
	}

	// PIPELINE
	// ----------------------------------

	stage("Creating Deployment") {
		for (int i=0; i < namespaceList.length; i++) {
			def namespace = namespaceList[i].trim()
			k8sDeploymentFileTMP = "${k8sDeploymentFile}.${namespace}"
			try {
				sh "${kubectl} apply -f ${k8sDeploymentFileTMP}"
			} catch (e) {
				pipelineError[namespace] = true
				println("ERROR: Error when try to create a new deployment or modified existed, file: ${k8sDeploymentFileTMP}")
			}
		}
	}

	stage("Checking Deployment") {
		if( params.CHECK_DEPLOYMENT ) {
			for (int i=0; i < namespaceList.length; i++) {
				def namespace = namespaceList[i].trim()
				try {
					timeout(time:2, unit:'MINUTES') {
						sh "${kubectl} rollout status deploy/${SERVICE_NAME} -n ${namespace}"
					}
				} catch (e) {
					pipelineError[namespace] = true
					println("ERROR: Timeout checking status of deployment or deployment failed")
					try { sh "${kubectl} describe deploy/${SERVICE_NAME} -n ${namespace}" } catch(err) { println(err) }
					try { sh "${kubectl} logs deploy/${SERVICE_NAME} -n ${namespace}" } catch(err) { println(err) }
				}
			}
		} else {
			println("The user disabled the deployment check.");
		}
	}

	stage("Creating Service and Ingress") {
		if( params.CREATE_SERVICE_INGRESS ) {
			for (int i=0; i < namespaceList.length; i++) {
				def namespace = namespaceList[i].trim()
				if( !pipelineError[namespace] ) {
					k8sIngressFileTMP = "${k8sIngressFile}.${namespace}"
					try {
						sh "${kubectl} apply -f ${k8sIngressFileTMP}"
					}
					catch (r) {
						pipelineError[namespace] = true
						println("ERROR: Error when try to create the service and ingress, file: ${k8sIngressFileTMP}")
					}
				}
			}
		} else {
			println("The user disabled the creation of service and ingress.");
		}
	}

	stage("Checking Errors on Pipeline") {
		println("------------------------------------------------------")
		println("PIPELINE STATUS")
		println("------------------------------------------------------")
		def pipelineStatus = true
		for (int i=0; i < namespaceList.length; i++) {
			def namespace = namespaceList[i].trim()
			if( pipelineError[namespace] ) {
				println("NAMESPACE: ${namespace} - STATUS: FAIL")
				pipelineStatus = false
			} else {
				println("NAMESPACE: ${namespace} - STATUS: SUCCESS")
			}
        }
            if (ENVIRONMENT=="aws-dev") {
            	for (int i=0; i < namespaceList.length; i++) {
            		def namespace = namespaceList[i].trim()
            		println("K8S Deployment (${namespace}): https://kube-ui.io/api/v1/namespaces/kube-system/services/kubernetes-dashboard/proxy/#!/deployment/${namespace}/${SERVICE_NAME}?namespace=${namespace}")
                }
            }
            if (ENVIRONMENT=="aws") {
                for (int i=0; i < namespaceList.length; i++) {
               	    def namespace = namespaceList[i].trim()
                    println("K8S Deployment (${namespace}): https://api.kube.io/api/v1/namespaces/kube-system/services/kubernetes-dashboard/proxy/#!/deployment/${namespace}/${SERVICE_NAME}?namespace=${namespace}")
                }
            }
            if (ENVIRONMENT=="bare") {
                for (int i=0; i < namespaceList.length; i++) {
                    def namespace = namespaceList[i].trim()
                    println("K8S Deployment (${namespace}): https://kube-bare/api/v1/namespaces/kube-system/services/kubernetes-dashboard/proxy/#!/deployment/${namespace}/${SERVICE_NAME}?namespace=${namespace}")
                }
            }
            if( !pipelineStatus ) {
                // Error function produce a break on the pipeline and returns red box on Jenkins and Build status failure
                println("Troubleshooting:")
                println("- Take a look at 'K8S Deployment' link to check the deployment.")
                println("- Sometimes the deployment takes more time to be ready and Jenkins only wait 2 minutes.")
                error("- Check the logs")
            }
    }

}
