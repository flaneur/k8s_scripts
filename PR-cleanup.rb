#!/usr/bin/env ruby

require 'bundler/setup'
require 'yaml'
require 'json'
require 'logger'
require 'slop'
require 'deep_merge'
require 'base64'
require 'rest-client'

namespaces = ["development","testing"]

LOG = Logger.new(STDOUT)
LOG.level = Logger::INFO
LOG.formatter = proc do |severity, datetime, _, msg|
  "#{severity} #{datetime.strftime('%d %b %Y %H:%M:%S')}: #{msg}\n"
end

def bb_pr_closed?(options = {})
    now = Time.now
    created = Time.parse(options[:creation])
    timeDiff = now - created
    timeDiffInDays = timeDiff/86400
    if timeDiffInDays > 7
        true
    end
  # Checks if pull request is closed on Bitbucket
  options[:repo] = repo_override(options[:repo])
  auth = 'Basic ' + Base64.encode64("#{options[:user]}:#{options[:password]}").chomp
  url = "https://bitbucket/rest/api/1.0/projects/#{options[:project]}/repos/#{options[:repo]}/pull-requests/#{options[:pr_num]}"
  resp = RestClient::Resource.new(url, headers: {'Authorization' => auth})
  begin
    JSON.parse(resp.get.body)['closed']
  rescue
    puts "Failed to get PR info for #{options[:project]}/#{options[:repo]} ##{options[:pr_num]}"
    false
  end
end

def get_bb_projects(options = {})
  auth = 'Basic ' + Base64.encode64("#{options[:user]}:#{options[:password]}").chomp
  url = "https://bitbucket/rest/api/1.0/projects?size=1000&limit=1000"
  resp = RestClient::Resource.new(url, headers: {'Authorization' => auth})
  begin
    projects = JSON.parse(resp.get.body)
    repos = []
    projects['values'].each do |item|
        project = item['key']
        url = "https://bitbucket/rest/api/1.0/projects/" + item['key'] + "/repos?size=1000&limit=1000"
        resp = RestClient::Resource.new(url, headers: {'Authorization' => auth})
        repositories = JSON.parse(resp.get.body)
        repositories['values'].each do |repo|
            repos.push(repo)
        end
    end
    repos
  rescue Exception => e
    puts "Failed to get BB projects"
    puts "uncaught #{e} exception while handling connection: #{e.message}"
    puts "Stack trace: #{backtrace.map {|l| "  #{l}\n"}.join}"
    false
  end

end

def repo_override(repo)
    case repo
        when "data-service"
            "data"
        when "promo-service"
            "promo-api"
        when "api-service"
            "recom-backend"
        else
            return repo
        end
end

def repository?(metadata = {})
  if metadata['labels']
    metadata['labels']['repository']
  else
    nil
  end
end

def get_project(service)
    $projects.each do |repo|
        if repo['slug']==repo_override(service)
            return repo['project']['key']
        end
    end
    false
end

opts = Slop.parse(help: true, strict: true) do
  banner "Clean up closed PRs on bitbucket.upc.biz \n
          Usage: #{__FILE__} [options]"

  on :U, :user=, 'Bitbucket user for API requests'
  on :P, :password=, 'Bitbucket password for API requests'
  on :K, :kubeconfig=, 'Kube Config'
  run do |options, _|
    raise 'You must provide BB user && pass (see --help).' unless options[:user] || options[:password] || options[:kubeconfig]
    kubectl = "kubectl --kubeconfig=#{options[:kubeconfig]}"

    #get projects
    LOG.info("fetching list of repositories")
    $projects = get_bb_projects(options)

    # cleanup deployments
    for namespace in namespaces
      kube_output = JSON.parse(`#{kubectl} get deployments --namespace=#{namespace} --output=json`)
      kube_output['items'].each do |item|
        deployment_name = item['metadata']['name']
        if deployment_name =~ /^[A-Za-z0-9-]+-pr[0-9]+$/
          params = {}
          params[:pr_num] = deployment_name.match(/.*-pr(\d*).*/i).captures[0]
          params[:project] = get_project(repository?(item['metadata']) || deployment_name.sub('-pr' + params[:pr_num], ''))
          params[:repo] = repository?(item['metadata']) || deployment_name.sub('-pr' + params[:pr_num], '')
          params[:user] = options[:user]
          params[:password] = options[:password]
          params[:creation] = item['metadata']['creationTimestamp']
          if bb_pr_closed?(params)
            LOG.info("PR #{params[:pr_num]} for #{params[:repo]} is closed! Deleting from k8s #{namespace} namespace!")
            `#{kubectl} delete deployment #{deployment_name} --namespace=#{namespace}`
            `#{kubectl} delete cronjob #{deployment_name} --namespace=#{namespace}`
            `#{kubectl} delete service #{deployment_name} --namespace=#{namespace}`
            `#{kubectl} delete ingress #{deployment_name} --namespace=#{namespace}`
          end
        end
      end
    # cleanup cronjobs
      kube_output = JSON.parse(`#{kubectl} get cronjob --namespace=#{namespace} --output=json`)
      kube_output['items'].each do |item|
        deployment_name = item['metadata']['name']
        if deployment_name =~ /^[A-Za-z0-9-]+-pr[0-9]+$/
          params = {}
          params[:pr_num] = deployment_name.match(/.*-pr(\d*).*/i).captures[0]
          params[:project] = get_project(repository?(item['metadata']) || deployment_name.sub('-pr' + params[:pr_num], ''))
          params[:repo] = repository?(item['metadata']) || deployment_name.sub('-pr' + params[:pr_num], '')
          params[:user] = options[:user]
          params[:password] = options[:password]
          params[:creation] = item['metadata']['creationTimestamp']
          if bb_pr_closed?(params)
            LOG.info("PR #{params[:pr_num]} for #{params[:repo]} is closed! Deleting from k8s #{namespace} namespace!")
            `#{kubectl} delete deployment #{deployment_name} --namespace=#{namespace}`
            `#{kubectl} delete cronjob #{deployment_name} --namespace=#{namespace}`
            if params[:repo]=="metadata-enricher"
              `#{kubectl} delete cronjob #{deployment_name}-imdb-offline-import --namespace=#{namespace}`
            end
            `#{kubectl} delete service #{deployment_name} --namespace=#{namespace}`
            `#{kubectl} delete ingress #{deployment_name} --namespace=#{namespace}`
          end
        end
      end
    # cleanup empty replica sets
      kube_output = JSON.parse(`#{kubectl} get rs --namespace=#{namespace} --output=json`)
      kube_output['items'].each do |item|
        rs_name = item['metadata']['name']
        rs_replicas = item['spec']['replicas']
        if rs_replicas == 0
          LOG.info("Deleting #{rs_name} because it has #{rs_replicas} replicas from k8s #{namespace} namespace!")
          `#{kubectl} delete rs #{rs_name} --namespace=#{namespace}`
        end
      end
    end
  end
end
