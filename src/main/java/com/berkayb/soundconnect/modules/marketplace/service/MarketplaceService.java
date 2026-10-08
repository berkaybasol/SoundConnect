package com.berkayb.soundconnect.modules.marketplace.service;

import com.berkayb.soundconnect.modules.marketplace.MarketplaceTypes.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceResponses.*;
import com.berkayb.soundconnect.modules.marketplace.media.MarketplaceMediaLifecycle;
import com.berkayb.soundconnect.modules.marketplace.repository.MarketplaceRepository;
import com.berkayb.soundconnect.modules.marketplace.repository.MarketplaceRepository.CategoryRow;
import com.berkayb.soundconnect.modules.marketplace.repository.MarketplaceRepository.Stored;
import com.berkayb.soundconnect.modules.marketplace.support.MarketplaceAccess;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.ownership.ProfileOwnershipResolver;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class MarketplaceService {
    private static final int MAX_DRAFTS=30;
    private static final int MAX_PUBLISHED=20;
    private static final long MAX_PRICE=100_000_000_000L;
    private final MarketplaceRepository repository;
    private final MarketplaceAccess access;
    private final ProfileOwnershipResolver profiles;
    private final LocationEntityFinder locations;
    private final MarketplaceMediaLifecycle media;

    @Transactional(readOnly=true)
    public List<Category> categories(UUID viewer) {
        access.requireBackstage(viewer);
        List<CategoryRow> rows=repository.categories();
        return rows.stream().filter(row->row.parentId()==null && row.active()).map(root->new Category(root.id(),root.code(),root.name(),
                rows.stream().filter(child->root.id().equals(child.parentId()) && child.active())
                        .map(child->new Category(child.id(),child.code(),child.name(),List.of())).toList())).toList();
    }
    @Transactional(readOnly=true)
    public PageResponse<Listing> discovery(UUID viewer,Filter filter,int page,int size) {
        access.requireBackstage(viewer);validatePage(page,size);validateFilter(filter);
        return repository.page(viewer,filter,false,false,null,page,size);
    }
    @Transactional(readOnly=true)
    public PageResponse<Listing> mine(UUID viewer,Status status,int page,int size) {
        access.requireBackstage(viewer);validatePage(page,size);
        return repository.page(viewer,null,true,false,status,page,size);
    }
    @Transactional(readOnly=true)
    public PageResponse<Listing> saved(UUID viewer,int page,int size) {
        access.requireBackstage(viewer);validatePage(page,size);
        return repository.page(viewer,null,false,true,null,page,size);
    }
    @Transactional(readOnly=true)
    public Listing detail(UUID viewer,UUID id) {
        access.requireBackstage(viewer);
        return visible(viewer,id,false,true);
    }
    public Listing createDraft(UUID viewer,Draft request) {
        requireMutation(viewer);
        if(request==null || request.clientRequestId()==null) throw invalid();
        Optional<UUID> replay=repository.draftReplay(viewer,request.clientRequestId());
        if(replay.isPresent()) return response(viewer,replay.get());
        if(repository.countByOwner(viewer,Status.DRAFT)>=MAX_DRAFTS) throw failure(ErrorType.MARKETPLACE_LIMIT_REACHED);
        var owned=profiles.resolveOwnedProfiles(viewer,Set.of(ProfileType.MUSICIAN,ProfileType.STUDIO,ProfileType.VENUE));
        if(owned.size()!=1) throw failure(ErrorType.MARKETPLACE_FORBIDDEN);
        UUID id=repository.insertDraft(viewer,request.clientRequestId(),owned.getFirst());
        return response(viewer,id);
    }
    public Listing update(UUID viewer,UUID id,Update request) {
        requireMutation(viewer);
        Listing existing=owned(viewer,id);
        if(existing.status()!=Status.DRAFT && existing.status()!=Status.PUBLISHED && existing.status()!=Status.WITHDRAWN)
            throw failure(ErrorType.MARKETPLACE_STATE_CONFLICT);
        if(request==null) throw invalid();
        version(existing.version(),request.expectedVersion());
        Update value=normalize(request);
        validate(value,existing.status()!=Status.DRAFT);
        media.validateAttachments(viewer,id,value.photoIds());
        List<UUID> removed=existing.photos().stream().map(Photo::assetId).filter(asset->!value.photoIds().contains(asset)).toList();
        repository.update(id,value);
        repository.replacePhotos(id,value.photoIds());
        media.deleteDetachedAssets(viewer,id,removed);
        return response(viewer,id);
    }
    public Listing publish(UUID viewer,UUID id,Version request) {
        requireMutation(viewer);
        Listing listing=owned(viewer,id);
        if(replayed(listing,Status.PUBLISHED,request)) return listing;
        version(listing.version(),request==null?null:request.expectedVersion());
        if(listing.status()!=Status.DRAFT && listing.status()!=Status.WITHDRAWN) throw failure(ErrorType.MARKETPLACE_STATE_CONFLICT);
        if(repository.countByOwner(viewer,Status.PUBLISHED)>=MAX_PUBLISHED) throw failure(ErrorType.MARKETPLACE_LIMIT_REACHED);
        Update stored=asUpdate(listing);validate(stored,true);media.validateAttachments(viewer,id,stored.photoIds());
        repository.transition(id,Status.PUBLISHED);
        return response(viewer,id);
    }
    public Listing sold(UUID viewer,UUID id,Version request) {return close(viewer,id,request,Status.SOLD);}
    public Listing withdraw(UUID viewer,UUID id,Version request) {return close(viewer,id,request,Status.WITHDRAWN);}
    private Listing close(UUID viewer,UUID id,Version request,Status target) {
        requireMutation(viewer);
        Listing listing=owned(viewer,id);
        if(replayed(listing,target,request)) return listing;
        version(listing.version(),request==null?null:request.expectedVersion());
        if(listing.status()!=Status.PUBLISHED) throw failure(ErrorType.MARKETPLACE_STATE_CONFLICT);
        repository.transition(id,target);
        return response(viewer,id);
    }
    public void deleteDraft(UUID viewer,UUID id,long expectedVersion) {
        requireMutation(viewer);
        Listing listing=owned(viewer,id,false);version(listing.version(),expectedVersion);
        if(listing.status()!=Status.DRAFT) throw failure(ErrorType.MARKETPLACE_STATE_CONFLICT);
        repository.replacePhotos(id,List.of());
        media.deleteListingMedia(id);
        repository.delete(id);
    }
    public void save(UUID viewer,UUID id) {
        requireMutation(viewer);
        Listing listing=visible(viewer,id,true,false);
        if(listing.isOwner()) throw failure(ErrorType.MARKETPLACE_FORBIDDEN);
        repository.save(viewer,id);
    }
    public void unsave(UUID viewer,UUID id) {requireMutation(viewer);repository.unsave(viewer,id);}
    public ReportReceipt report(UUID viewer,UUID id,Report request) {
        requireMutation(viewer);
        if(request==null || request.clientRequestId()==null || request.reason()==null) throw invalid();
        String description=clean(request.description(),1000);
        if(request.reason()==ReportReason.OTHER && (description==null || textLength(description)<5)) throw invalid();
        Report normalized=new Report(request.reason(),description,request.clientRequestId());
        Optional<AdminReport> replay=repository.reportReplay(viewer,request.clientRequestId());
        if(replay.isPresent()) {
            AdminReport old=replay.get();
            if(!Objects.equals(old.listingId(),id) || old.reason()!=request.reason() || !Objects.equals(old.description(),description))
                throw failure(ErrorType.MARKETPLACE_IDEMPOTENCY_CONFLICT);
            return new ReportReceipt(old.id(),old.status());
        }
        Listing listing=visible(viewer,id,true,false);
        if(listing.isOwner()) throw failure(ErrorType.MARKETPLACE_FORBIDDEN);
        if(repository.alreadyReported(viewer,id)) throw failure(ErrorType.MARKETPLACE_REPORT_DUPLICATE);
        AdminReport report=repository.createReport(viewer,id,normalized,listing);
        return new ReportReceipt(report.id(),report.status());
    }
    @Transactional(readOnly=true)
    public PageResponse<AdminReport> reports(UUID viewer,ReportStatus status,int page,int size) {
        access.requireModerator(viewer);validatePage(page,size);return repository.reports(status,page,size);
    }
    public AdminReport review(UUID viewer,UUID reportId,Review request) {
        access.requireModerator(viewer);
        repository.lockUser(viewer);
        access.requireModerator(viewer);
        if(request==null || request.decision()==null) throw invalid();
        String note=clean(request.resolutionNote(),1000);
        if(note==null || textLength(note)<5) throw invalid();
        AdminReport found=repository.report(reportId,false).orElseThrow(()->failure(ErrorType.MARKETPLACE_REPORT_NOT_FOUND));
        // All review paths lock listing before report, matching reporter/editor asset lock order.
        if(found.listingId()!=null) repository.find(found.listingId(),viewer,true);
        AdminReport current=repository.report(reportId,true).orElseThrow(()->failure(ErrorType.MARKETPLACE_REPORT_NOT_FOUND));
        if(current.status()!=ReportStatus.OPEN) {
            if(current.decision()==request.decision() && Objects.equals(current.resolutionNote(),note)
                    && request.expectedVersion()!=null && current.version()==request.expectedVersion()+1) return current;
            throw failure(ErrorType.MARKETPLACE_STATE_CONFLICT);
        }
        version(current.version(),request.expectedVersion());
        if(request.decision()==ReportDecision.REMOVE_LISTING && current.listingId()!=null) repository.transition(current.listingId(),Status.MODERATED);
        repository.resolveReport(reportId,viewer,new Review(request.expectedVersion(),request.decision(),note));
        return repository.report(reportId,false).orElseThrow();
    }
    private void requireMutation(UUID viewer) {
        access.requireBackstage(viewer);
        repository.lockUser(viewer);
        access.requireBackstage(viewer);
    }
    private Listing owned(UUID viewer,UUID id) {
        return owned(viewer,id,true);
    }
    private Listing owned(UUID viewer,UUID id,boolean requireSellerProfile) {
        Stored stored=repository.find(id,viewer,true).orElseThrow(()->failure(ErrorType.MARKETPLACE_NOT_FOUND));
        if(!stored.listing().seller().userId().equals(viewer)) throw failure(ErrorType.MARKETPLACE_NOT_FOUND);
        if(requireSellerProfile && !stored.sellerEligible()) throw failure(ErrorType.MARKETPLACE_FORBIDDEN);
        return MarketplaceRepository.forViewer(stored.listing(),viewer);
    }
    private Listing visible(UUID viewer,UUID id,boolean lock,boolean allowOwnerInactive) {
        Stored row=repository.find(id,viewer,lock).orElseThrow(()->failure(ErrorType.MARKETPLACE_NOT_FOUND));
        boolean owner=row.listing().seller().userId().equals(viewer);
        if(!(owner && allowOwnerInactive) && (row.listing().status()!=Status.PUBLISHED || !row.sellerEligible()))
            throw failure(ErrorType.MARKETPLACE_NOT_FOUND);
        return MarketplaceRepository.forViewer(row.listing(),viewer);
    }
    private Listing response(UUID viewer,UUID id) {
        return MarketplaceRepository.forViewer(repository.find(id,viewer,false).orElseThrow(()->failure(ErrorType.MARKETPLACE_NOT_FOUND)).listing(),viewer);
    }
    private static boolean replayed(Listing listing,Status target,Version request) {
        return request!=null && request.expectedVersion()!=null && request.expectedVersion()>=0
                && listing.status()==target && listing.version()==request.expectedVersion()+1;
    }
    private static void version(long actual,Long expected) {
        if(expected==null || expected<0) throw invalid();
        if(actual!=expected) throw failure(ErrorType.MARKETPLACE_VERSION_CONFLICT);
    }
    private void validateFilter(Filter filter) {
        if(filter==null) return;
        clean(filter.q(),100);
        if(filter.minPriceMinor()!=null && filter.minPriceMinor()<0 || filter.maxPriceMinor()!=null && filter.maxPriceMinor()<0
                || filter.minPriceMinor()!=null && filter.maxPriceMinor()!=null && filter.minPriceMinor()>filter.maxPriceMinor()) throw invalid();
        if(filter.categoryId()!=null && repository.category(filter.categoryId()).isEmpty()) throw invalid();
        if(filter.cityId()!=null) locations.getCity(filter.cityId());
        if(filter.districtId()!=null) {
            var district=locations.getDistrict(filter.districtId());
            if(filter.cityId()!=null && !filter.cityId().equals(district.getCity().getId())) throw invalid();
        }
    }
    private void validate(Update value,boolean complete) {
        if(value.photoIds()==null || value.photoIds().size()>8 || value.photoIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(value.photoIds()).size()!=value.photoIds().size() || value.negotiable()==null) throw invalid();
        if(value.priceMinor()!=null && (value.priceMinor()<1 || value.priceMinor()>MAX_PRICE)) throw invalid();
        if(value.categoryId()!=null) {
            CategoryRow category=repository.category(value.categoryId()).orElseThrow(MarketplaceService::invalid);
            if(!category.active() || category.parentId()==null || repository.category(category.parentId()).filter(CategoryRow::active).isEmpty()) throw invalid();
        }
        if(value.districtId()!=null) locations.getDistrict(value.districtId());
        if(complete && (value.title()==null || textLength(value.title())<5 || value.description()==null || textLength(value.description())<10
                || value.categoryId()==null || value.condition()==null || value.priceMinor()==null || value.districtId()==null
                || value.deliveryMethod()==null || value.photoIds().isEmpty())) throw failure(ErrorType.MARKETPLACE_INCOMPLETE);
    }
    static Update normalize(Update value) {
        return new Update(value.expectedVersion(),clean(value.title(),120),clean(value.description(),4000),value.categoryId(),
                clean(value.brand(),80),clean(value.model(),100),value.condition(),value.priceMinor(),value.districtId(),
                value.photoIds()==null?null:Collections.unmodifiableList(new ArrayList<>(value.photoIds())),value.negotiable(),value.deliveryMethod());
    }
    private static Update asUpdate(Listing listing) {
        return new Update(listing.version(),listing.title(),listing.description(),listing.category()==null?null:listing.category().id(),
                listing.brand(),listing.model(),listing.condition(),listing.priceMinor(),listing.district()==null?null:listing.district().id(),
                listing.photos().stream().map(Photo::assetId).toList(),listing.negotiable(),listing.deliveryMethod());
    }
    static String clean(String value,int max) {
        if(value==null) return null;
        String cleaned=value.strip();
        if(textLength(cleaned)>max || cleaned.indexOf('\0')>=0) throw invalid();
        return cleaned.isEmpty()?null:cleaned;
    }
    // Match PostgreSQL char_length/varchar limits, including supplementary Unicode characters.
    private static int textLength(String value) {return value.codePointCount(0,value.length());}
    private static void validatePage(int page,int size) {if(page<0 || page>1000 || size<1 || size>50) throw invalid();}
    private static SoundConnectException invalid(){return failure(ErrorType.MARKETPLACE_INVALID);}
    private static SoundConnectException failure(ErrorType type){return new SoundConnectException(type);}
}
