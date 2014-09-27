package com.pmease.gitplex.web.page.repository.pullrequest;

import static com.pmease.gitplex.core.model.PullRequest.Status.INTEGRATED;
import static com.pmease.gitplex.core.model.PullRequest.Status.PENDING_UPDATE;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.RestartResponseException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextArea;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.mapper.parameter.PageParameters;

import com.google.common.base.Preconditions;
import com.pmease.commons.git.Change;
import com.pmease.commons.git.Commit;
import com.pmease.commons.git.Git;
import com.pmease.commons.hibernate.dao.Dao;
import com.pmease.commons.loader.AppLoader;
import com.pmease.commons.util.FileUtils;
import com.pmease.commons.wicket.behavior.DisableIfBlankBehavior;
import com.pmease.commons.wicket.component.backtotop.BackToTop;
import com.pmease.commons.wicket.component.tabbable.AjaxActionTab;
import com.pmease.commons.wicket.component.tabbable.Tab;
import com.pmease.commons.wicket.component.tabbable.Tabbable;
import com.pmease.gitplex.core.GitPlex;
import com.pmease.gitplex.core.comment.InlineCommentSupport;
import com.pmease.gitplex.core.gatekeeper.checkresult.Approved;
import com.pmease.gitplex.core.manager.BranchManager;
import com.pmease.gitplex.core.manager.PullRequestManager;
import com.pmease.gitplex.core.manager.UserManager;
import com.pmease.gitplex.core.model.Branch;
import com.pmease.gitplex.core.model.OldCommitComment;
import com.pmease.gitplex.core.model.PullRequest;
import com.pmease.gitplex.core.model.PullRequest.CloseStatus;
import com.pmease.gitplex.core.model.PullRequestUpdate;
import com.pmease.gitplex.core.model.Repository;
import com.pmease.gitplex.core.model.User;
import com.pmease.gitplex.web.component.branch.AffinalBranchSingleChoice;
import com.pmease.gitplex.web.component.branch.BranchLink;
import com.pmease.gitplex.web.component.commit.CommitsTablePanel;
import com.pmease.gitplex.web.component.diff.CompareResultPanel;
import com.pmease.gitplex.web.page.repository.NoCommitsPage;
import com.pmease.gitplex.web.page.repository.RepositoryPage;
import com.pmease.gitplex.web.page.repository.code.commit.diff.CommitCommentsAware;

@SuppressWarnings("serial")
public class NewRequestPage extends RepositoryPage implements CommitCommentsAware {

	private AffinalBranchSingleChoice targetChoice, sourceChoice;
	
	private IModel<List<Commit>> commitsModel;
	
	private PullRequest pullRequest;
	
	public static PageParameters paramsOf(Repository repository, Branch source, Branch target) {
		PageParameters params = paramsOf(repository);
		params.set("source", source.getId());
		params.set("target", target.getId());
		return params;
	}
	
	public NewRequestPage(PageParameters params) {
		super(params);
		
		if (!getRepository().git().hasCommits()) 
			throw new RestartResponseException(NoCommitsPage.class, paramsOf(getRepository()));

		BranchManager branchManager = GitPlex.getInstance(BranchManager.class);
		Dao dao = AppLoader.getInstance(Dao.class);
		
		RepositoryPage page = (RepositoryPage) getPage();

		Branch target, source = null;
		if (params.get("target").toString() != null) {
			target = dao.load(Branch.class, params.get("target").toLongObject());
		} else {
			if (page.getRepository().getForkedFrom() != null) {
				target = branchManager.findDefault(page.getRepository().getForkedFrom());
			} else {
				target = branchManager.findDefault(page.getRepository());
			}
		}
		if (params.get("source").toString() != null) {
			source = dao.load(Branch.class, params.get("source").toLongObject());
		} else {
			if (page.getRepository().getForkedFrom() != null) {
				source = branchManager.findDefault(page.getRepository());
			} else {
				for (Branch each: page.getRepository().getBranches()) {
					if (!each.equals(target)) {
						source = each;
						break;
					}
				}
				if (source == null)
					source = target;
			}
		}

		User currentUser = AppLoader.getInstance(UserManager.class).getCurrent();
		
		pullRequest = GitPlex.getInstance(PullRequestManager.class).findOpen(target, source);
		
		if (pullRequest == null) {
			pullRequest = new PullRequest();
			pullRequest.setTarget(target);
			pullRequest.setSource(source);
			pullRequest.setSubmitter(currentUser);
			
			PullRequestUpdate update = new PullRequestUpdate();
			pullRequest.getUpdates().add(update);
			update.setRequest(pullRequest);
			update.setUser(currentUser);
			update.setHeadCommitHash(source.getHeadCommitHash());
			pullRequest.setUpdateDate(new Date());

			if (target.getRepository().equals(source.getRepository())) {
				pullRequest.setBaseCommitHash(pullRequest.git().calcMergeBase(target.getHeadCommitHash(), source.getHeadCommitHash()));			
				if (target.getRepository().git().isAncestor(source.getHeadCommitHash(), target.getHeadCommitHash())) {
					pullRequest.setCloseStatus(CloseStatus.INTEGRATED);
					pullRequest.setCheckResult(new Approved("Already integrated."));
				} else {
					pullRequest.setCheckResult(target.getRepository().getGateKeeper().checkRequest(pullRequest));
				}
			} else {
				Git sandbox = new Git(FileUtils.createTempDir());
				pullRequest.setSandbox(sandbox);
				sandbox.clone(target.getRepository().git(), false, true, true, pullRequest.getTarget().getName());
				sandbox.reset(null, null);

				sandbox.fetch(source.getRepository().git());
				
				pullRequest.setBaseCommitHash(pullRequest.git().calcMergeBase(target.getHeadCommitHash(), source.getHeadCommitHash()));			

				if (sandbox.isAncestor(source.getHeadCommitHash(), target.getHeadCommitHash())) {
					pullRequest.setCloseStatus(CloseStatus.INTEGRATED);
					pullRequest.setCheckResult(new Approved("Already integrated."));
				} else {
					pullRequest.setCheckResult(target.getRepository().getGateKeeper().checkRequest(pullRequest));
				}
			}
		}
		
		commitsModel = new LoadableDetachableModel<List<Commit>>() {

			@Override
			protected List<Commit> load() {
				return pullRequest.git().log(pullRequest.getBaseCommitHash(), 
						pullRequest.getLatestUpdate().getHeadCommitHash(), null, 0, 0);
			}
			
		};
		
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();

		setOutputMarkupId(true);
		
		IModel<Repository> currentRepositoryModel = new LoadableDetachableModel<Repository>() {

			@Override
			protected Repository load() {
				RepositoryPage page = (RepositoryPage) getPage();
				return page.getRepository();
			}
			
		};
		
		targetChoice = new AffinalBranchSingleChoice("target", currentRepositoryModel, 
				Model.of(pullRequest.getTarget())) {

			@Override
			protected void onChange(AjaxRequestTarget target) {
				super.onChange(target);
				setResponsePage(
						NewRequestPage.class, 
						paramsOf(getRepository(), sourceChoice.getModelObject(), targetChoice.getModelObject()));
			}
			
		};
		targetChoice.setRequired(true);
		add(targetChoice);
		
		sourceChoice = new AffinalBranchSingleChoice("source", currentRepositoryModel, 
				Model.of(pullRequest.getSource())) {

			@Override
			protected void onChange(AjaxRequestTarget target) {
				super.onChange(target);
				setResponsePage(
						NewRequestPage.class, 
						paramsOf(getRepository(), sourceChoice.getModelObject(), targetChoice.getModelObject()));
			}
			
		};
		sourceChoice.setRequired(true);
		add(sourceChoice);
		
		add(new Link<Void>("swap") {

			@Override
			public void onClick() {
				setResponsePage(
						NewRequestPage.class, 
						paramsOf(getRepository(), pullRequest.getTarget(), pullRequest.getSource()));
			}
			
		});
		
		Fragment fragment;
		if (pullRequest.getId() != null) {
			fragment = newOpenedFrag();
		} else if (pullRequest.getSource().equals(pullRequest.getTarget())) {
			fragment = newSameBranchFrag();
		} else if (pullRequest.getStatus() == INTEGRATED) {
			fragment = newIntegratedFrag();
		} else if (pullRequest.getStatus() == PENDING_UPDATE) {
			fragment = newRejectedFrag();
		} else {
			fragment = newCanSendFrag();
		}
		add(fragment);

		final IModel<Repository> repositoryModel = new AbstractReadOnlyModel<Repository>() {

			@Override
			public Repository getObject() {
				return pullRequest.getTarget().getRepository();
			}
			
		};
		
		List<Tab> tabs = new ArrayList<>();
		
		tabs.add(new AjaxActionTab(Model.of("Commits")) {
			
			@Override
			protected void onSelect(AjaxRequestTarget target, Component tabLink) {
				Component panel = newCommitsPanel();
				getPage().replace(panel);
				target.add(panel);
			}
			
		});

		tabs.add(new AjaxActionTab(Model.of("Changed Files")) {
			
			@Override
			protected void onSelect(AjaxRequestTarget target, Component tabLink) {
				Component panel = newChangedFilesPanel();
				getPage().replace(panel);
				target.add(panel);
			}
			
		});

		add(new Tabbable("tabs", tabs) {

			@Override
			protected String getCssClasses() {
				return "nav nav-tabs";
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				setVisible(pullRequest.getStatus() != INTEGRATED);
			}
			
		});
		
		add(new CommitsTablePanel("tabPanel", commitsModel, repositoryModel).setOutputMarkupId(true));

		add(new BackToTop("backToTop"));
	}
	
	private Component newCommitsPanel() {
		return new CommitsTablePanel("tabPanel", commitsModel, repoModel).setOutputMarkupId(true);
	}
	
	private Component newChangedFilesPanel() {
		return new CompareResultPanel("tabPanel", repoModel, pullRequest.getBaseCommitHash(), 
				pullRequest.getLatestUpdate().getHeadCommitHash(), null) {
			
			@Override
			protected void onChangeSelection(AjaxRequestTarget target, Change change) {
			}
			
			@Override
			protected InlineCommentSupport getInlineCommentSupport(Change change) {
				return null;
			}
		}.setOutputMarkupId(true);
	}

	private Fragment newOpenedFrag() {
		Fragment fragment = new Fragment("status", "openedFrag", this);
		fragment.add(new Label("requestInfo", "#" + pullRequest.getId() + ": " + pullRequest.getTitle()));
		fragment.add(new Link<Void>("viewRequest") {

			@Override
			public void onClick() {
				PageParameters params = RequestDetailPage.paramsOf(pullRequest);
				setResponsePage(RequestActivitiesPage.class, params);
			}
			
		});
		
		return fragment;
	}
	
	private Fragment newSameBranchFrag() {
		return new Fragment("status", "sameBranchFrag", this);
	}
	
	private Fragment newIntegratedFrag() {
		Fragment fragment = new Fragment("status", "integratedFrag", this);
		fragment.add(new BranchLink("sourceBranch", Model.of(pullRequest.getSource())));
		fragment.add(new BranchLink("targetBranch", Model.of(pullRequest.getTarget())));
		fragment.add(new Link<Void>("swapBranches") {

			@Override
			public void onClick() {
				setResponsePage(
						NewRequestPage.class, 
						paramsOf(getRepository(), pullRequest.getTarget(), pullRequest.getSource()));
			}
			
		});
		return fragment;
	}
	
	private Fragment newRejectedFrag() {
		Fragment fragment = new Fragment("status", "rejectedFrag", this);
		fragment.add(new ListView<String>("reasons", new LoadableDetachableModel<List<String>>() {

			@Override
			protected List<String> load() {
				return pullRequest.getCheckResult().getReasons();
			}
			
		}) {

			@Override
			protected void populateItem(ListItem<String> item) {
				item.add(new Label("reason", item.getModelObject()));
			}

		});
		
		return fragment;
	}

	private Fragment newCanSendFrag() {
		Fragment fragment = new Fragment("status", "canSendFrag", this);
		Form<?> form = new Form<Void>("form");
		fragment.add(form);
		
		form.add(new Button("send") {

			@Override
			public void onSubmit() {
				super.onSubmit();

				Dao dao = GitPlex.getInstance(Dao.class);
				Branch target = dao.load(Branch.class, pullRequest.getTarget().getId());
				Branch source = dao.load(Branch.class, pullRequest.getSource().getId());
				if (!target.getHeadCommitHash().equals(pullRequest.getTarget().getHeadCommitHash()) 
						|| !source.getHeadCommitHash().equals(pullRequest.getSource().getHeadCommitHash())) {
					getSession().warn("Either target branch or source branch has new commits just now, please re-check.");
					setResponsePage(NewRequestPage.class, paramsOf(getRepository(), source, target));
				} else {
					pullRequest.setSource(source);
					pullRequest.setTarget(target);
					pullRequest.getVoteInvitations().clear();
					
					pullRequest.setAutoIntegrate(false);
					
					GitPlex.getInstance(PullRequestManager.class).send(pullRequest);
					
					setResponsePage(RequestActivitiesPage.class, RequestActivitiesPage.paramsOf(pullRequest));
				}
			}
			
		});
		
		form.add(new TextField<String>("title", new IModel<String>() {

			@Override
			public void detach() {
			}

			@Override
			public String getObject() {
				if (pullRequest.getTitle() == null) {
					List<Commit> commits = commitsModel.getObject();
					Preconditions.checkState(!commits.isEmpty());
					if (commits.size() == 1)
						pullRequest.setTitle(commits.get(0).getSubject());
					else
						pullRequest.setTitle(pullRequest.getSource().getName());
				}
				return pullRequest.getTitle();
			}

			@Override
			public void setObject(String object) {
				pullRequest.setTitle(object);
			}
			
		}).setRequired(true).add(new DisableIfBlankBehavior(form.get("send"))));
		
		form.add(new TextArea<String>("comment", new IModel<String>() {

			@Override
			public void detach() {
			}

			@Override
			public String getObject() {
				return pullRequest.getDescription();
			}

			@Override
			public void setObject(String object) {
				pullRequest.setDescription(object);
			}
			
		}));

		return fragment;
	}

	@Override
	protected void onDetach() {
		commitsModel.detach();

		if (pullRequest != null && pullRequest.getSandbox() != null) {
			FileUtils.deleteDir(pullRequest.getSandbox().repoDir());
			pullRequest.setSandbox(null);
		}

		super.onDetach();
	}

	@Override
	public List<OldCommitComment> getCommitComments() {
		return new ArrayList<>();
	}

	@Override
	public boolean isShowInlineComments() {
		return false;
	}

	@Override
	public boolean canAddComments() {
		return false;
	}
}